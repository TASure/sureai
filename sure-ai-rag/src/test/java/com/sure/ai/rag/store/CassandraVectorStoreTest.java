/*
 * Copyright (c) 2026 sureai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sure.ai.rag.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.cassandra.CassandraVectorStore;
import com.sure.ai.rag.store.filter.FilterExpression;

/**
 * {@link CassandraVectorStore} 单元测试：本地 {@link ServerSocket} mock CQL 二进制协议 v4，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class CassandraVectorStoreTest {

	private static final String KS = "testks";
	private static final String TABLE = "docs";

	private MockCassandra mock;

	/** 启动本地 mock。 */
	@Before
	public void setUp() throws IOException {
		this.mock = new MockCassandra();
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.mock.close();
	}

	private CassandraVectorStore.Builder baseBuilder() {
		return CassandraVectorStore.builder().host("127.0.0.1").port(mock.port())
				.keyspace(KS).table(TABLE);
	}

	private static Vector sampleVector(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f, 0.3f }, "text-" + id,
				Map.of("source", id + ".pdf"));
	}

	/** INSERT：CQL 含 qualified 表名、map 字面量、vector 字面量。 */
	@Test
	public void testAddAll() {
		baseBuilder().build().addAll(List.of(sampleVector("v1")));
		String cql = mock.lastQuery();
		assertTrue(cql.startsWith("INSERT INTO"));
		assertTrue(cql.contains("metadata"));
		assertTrue(cql.contains("[0.1,0.2,0.3]"));
		assertTrue(cql.contains("'v1'"));
	}

	/** SELECT：解析 Rows（varchar/map/double），sim 还原为余弦相似度降序。 */
	@Test
	public void testSearch() {
		mock.rowsResponder = cql -> mock.rowsResult(new String[] { "id", "text", "metadata", "sim" },
				new byte[][] {
						bytes("v1"), bytes("hello"), mock.mapBytes(new String[] { "source", "a.pdf" }),
								mock.doubleBytes(0.8) },
				new byte[][] {
						bytes("v2"), bytes("world"), mock.mapBytes(new String[] {}), mock.doubleBytes(0.9) });
		List<SimilaritySearchResult> results = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 2);
		String cql = mock.lastQuery();
		assertTrue(cql, cql.contains("ORDER BY") || cql.contains("ANN OF"));
		assertTrue(cql, cql.contains("ANN OF [0.1,0.2,0.3]"));
		assertEquals(2, results.size());
		// v2 sim 0.9 在前
		assertEquals("v2", results.get(0).id());
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("v1", results.get(1).id());
		assertEquals(0.8, results.get(1).score(), 1e-9);
		assertEquals("a.pdf", results.get(1).metadata().get("source"));
	}

	/** 带 filter：CQL WHERE 含 metadata['tenant']。 */
	@Test
	public void testSearchWithFilter() {
		mock.rowsResponder = cql -> mock.rowsResult(new String[] { "id", "text", "metadata", "sim" },
				new byte[][] { bytes("v1"), bytes("x"), mock.mapBytes(new String[] {}),
						mock.doubleBytes(0.5) });
		baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 1,
				FilterExpression.eq("tenant", "acme"));
		String cql = mock.lastQuery();
		assertTrue(cql, cql.contains("metadata['tenant'] = 'acme'"));
	}

	/** COUNT：size 解析首行首列。 */
	@Test
	public void testSize() {
		mock.rowsResponder = cql -> mock.rowsResult(new String[] { "c" },
				new byte[][] { bytes("7") });
		assertEquals(7, baseBuilder().build().size());
	}

	/** 服务端 ERROR 帧包装为 AiException。 */
	@Test
	public void testErrorThrows() {
		mock.responder = cql -> mock.errorFrame(8704, "Keyspace not found");
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 多轮 SASL（AUTH_CHALLENGE）抛明确异常。 */
	@Test
	public void testMultiRoundAuthThrows() {
		mock.forceChallenge();
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	/** 最小 CQL 二进制协议 v4 mock ServerSocket。 */
	static final class MockCassandra {
		private final ServerSocket server;
		private final AtomicReference<String> last = new AtomicReference<>();
		volatile java.util.function.Function<String, byte[]> responder = cql -> voidFrame();
		volatile java.util.function.Function<String, byte[]> rowsResponder;
		private volatile boolean challenge;

		MockCassandra() throws IOException {
			this.server = new ServerSocket(0);
			Thread t = new Thread(this::serve, "mock-cassandra");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		void forceChallenge() {
			this.challenge = true;
		}

		String lastQuery() {
			return last.get();
		}

		private void serve() {
			try {
				while (!server.isClosed()) {
					Socket s = server.accept();
					handle(s);
				}
			} catch (IOException ignored) {
				// 关闭。
			}
		}

		private void handle(Socket s) throws IOException {
			s.setSoTimeout(3000);
			DataInputStream in = new DataInputStream(s.getInputStream());
			try {
				// 读 STARTUP 帧（丢弃 body）
				readFrame(in);
				if (challenge) {
					// AUTHENTICATE -> 客户端 AUTH_RESPONSE -> AUTH_CHALLENGE
					writeFrame(s.getOutputStream(), 0x03, stringBody("org.apache.cassandra.auth.PasswordAuthenticator"));
					readFrame(in); // AUTH_RESPONSE
					writeFrame(s.getOutputStream(), 0x0E, new byte[0]); // AUTH_CHALLENGE
					return;
				}
				writeFrame(s.getOutputStream(), 0x02, new byte[0]); // READY
				while (!s.isClosed()) {
					Frame f = readFrame(in);
					if (f.opcode == -1) {
						break;
					}
					String cql = extractCql(f.body);
					last.set(cql);
					byte[] resp;
					if (rowsResponder != null && cql.startsWith("SELECT")) {
						resp = rowsResponder.apply(cql);
					} else {
						resp = responder.apply(cql);
					}
					s.getOutputStream().write(resp);
					s.getOutputStream().flush();
				}
			} finally {
				s.close();
			}
		}

		private static String extractCql(byte[] body) {
			ByteBuffer bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
			int len = bb.getInt();
			byte[] cql = new byte[len];
			bb.get(cql);
			return new String(cql, StandardCharsets.UTF_8);
		}

		// ===== 响应帧构造 =====

		private static byte[] voidFrame() {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			writeInt(body, 0x0001); // Void
			return frame(0x08, body.toByteArray());
		}

		private byte[] rowsResult(String[] cols, byte[][]... rows) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			writeInt(body, 0x0002); // Rows
			writeInt(body, 0x0001); // flags: global_table_spec
			writeInt(body, cols.length);
			writeString(body, KS);
			writeString(body, TABLE);
			for (int i = 0; i < cols.length; i++) {
				writeString(body, cols[i]);
				// 列类型：metadata 为 map<text,text>(0x21)，sim 为 double(0x07)，其余 varchar(0x0D)
				if ("metadata".equals(cols[i])) {
					writeShort(body, 0x0021);
					writeShort(body, 0x000D);
					writeShort(body, 0x000D);
				} else if ("sim".equals(cols[i])) {
					writeShort(body, 0x0007);
				} else {
					writeShort(body, 0x000D);
				}
			}
			writeInt(body, rows.length);
			for (byte[][] row : rows) {
				for (byte[] cell : row) {
					writeInt(body, cell.length);
					body.write(cell, 0, cell.length);
				}
			}
			return frame(0x08, body.toByteArray());
		}

		private byte[] errorFrame(int code, String msg) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			writeInt(body, code);
			writeString(body, msg);
			return frame(0x00, body.toByteArray());
		}

		private byte[] mapBytes(String[] kv) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			writeShort(body, kv.length / 2);
			for (int i = 0; i + 1 < kv.length; i += 2) {
				writeInt(body, kv[i].length());
				body.write(bytes(kv[i]), 0, kv[i].length());
				writeInt(body, kv[i + 1].length());
				body.write(bytes(kv[i + 1]), 0, kv[i + 1].length());
			}
			return body.toByteArray();
		}

		private static byte[] doubleBytes(double v) {
			return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(v).array();
		}

		private static byte[] stringBody(String s) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			writeString(body, s);
			return body.toByteArray();
		}

		private static byte[] frame(int opcode, byte[] body) {
			return frame(0x84, opcode, body);
		}

		private static byte[] frame(int version, int opcode, byte[] body) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(version);
			out.write(0x00);
			out.write(0x00);
			out.write(0x00);
			out.write(opcode);
			writeInt(out, body.length);
			out.write(body, 0, body.length);
			return out.toByteArray();
		}

		private static void writeFrame(java.io.OutputStream out, int opcode, byte[] body) throws IOException {
			out.write(frame(opcode, body));
			out.flush();
		}

		private static Frame readFrame(DataInputStream in) throws IOException {
			in.readUnsignedByte(); // version
			in.readUnsignedByte(); // flags
			in.readShort(); // stream
			int opcode = in.readUnsignedByte();
			int len = in.readInt();
			byte[] body = new byte[len];
			in.readFully(body);
			return new Frame(opcode, body);
		}

		private static void writeInt(ByteArrayOutputStream out, int v) {
			out.write((v >> 24) & 0xFF);
			out.write((v >> 16) & 0xFF);
			out.write((v >> 8) & 0xFF);
			out.write(v & 0xFF);
		}

		private static void writeShort(ByteArrayOutputStream out, int v) {
			out.write((v >> 8) & 0xFF);
			out.write(v & 0xFF);
		}

		private static void writeString(ByteArrayOutputStream out, String s) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			writeShort(out, b.length);
			out.write(b, 0, b.length);
		}

		void close() {
			try {
				server.close();
			} catch (IOException ignored) {
				// ignore
			}
		}
	}

	private static final class Frame {
		private final int opcode;
		private final byte[] body;

		Frame(int opcode, byte[] body) {
			this.opcode = opcode;
			this.body = body;
		}
	}
}
