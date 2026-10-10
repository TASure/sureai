/*
 * Copyright (c) 2026 sureai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
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
import java.util.ArrayList;
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

/**
 * {@link CassandraVectorStore} 错误/边界分支单元测试：扩展 {@link ServerSocket} mock 覆盖
 * 明文认证、启动期错误/未知 opcode、非 RESULT 帧、Set_keyspace 结果、分页标志、
 * null 单元格、损坏 map、UDT/tuple/list/map/set 列类型跳过、批量写入与 delete/clear。
 *
 * @author sureai
 * @since 2.6.0
 */
public class CassandraVectorStoreDeepTest {

	private MockCassandra mock;

	/** 启动 mock。 */
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
				.keyspace("testks").table("docs");
	}

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "text-" + id, Map.of("source", id + ".pdf"));
	}

	/** 批量多向量 + null 元素：逐条 INSERT，null 跳过。 */
	@Test
	public void testAddAllBatch() {
		baseBuilder().build().addAll(new java.util.ArrayList<>(java.util.Arrays.asList(v("v1"), null, v("v2"))));
		List<String> queries = mock.queries();
		long inserts = queries.stream().filter(q -> q.startsWith("INSERT")).count();
		assertEquals(2, inserts);
	}

	/** delete：DELETE CQL。 */
	@Test
	public void testDelete() {
		baseBuilder().build().delete("v1");
		assertTrue(mock.lastQuery().startsWith("DELETE FROM"));
	}

	/** clear：TRUNCATE。 */
	@Test
	public void testClear() {
		baseBuilder().build().clear();
		assertTrue(mock.lastQuery().startsWith("TRUNCATE"));
	}

	/** size：首行 c 非数字 → -1。 */
	@Test
	public void testSizeNonNumeric() {
		mock.rowsResponder = cql -> mock.rows(new Col[] { new Col("c", varcharType()) },
				new byte[][] { bytes("not-a-number") });
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 检索：minScore 过滤掉低分行；text 为 null 时置空串。 */
	@Test
	public void testSearchMinScoreAndNullText() {
		mock.rowsResponder = cql -> mock.rows(new Col[] {
				new Col("id", varcharType()), new Col("text", varcharType()),
				new Col("metadata", mapType()), new Col("sim", doubleType()) },
				row(bytes("low"), null, mock.mapBytes(new String[] {}), mock.doubleBytes(0.1)),
				row(bytes("high"), bytes("keep"), mock.mapBytes(new String[] {}), mock.doubleBytes(0.9)));
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("high", r.get(0).id());
	}

	/** 检索：metadata 非 map（null 单元格）→ 空元数据。 */
	@Test
	public void testSearchMetadataNullCell() {
		mock.rowsResponder = cql -> mock.rows(new Col[] {
				new Col("id", varcharType()), new Col("text", varcharType()),
				new Col("metadata", mapType()), new Col("sim", doubleType()) },
				row(bytes("v1"), bytes("t"), mock.nullBytes(), mock.doubleBytes(0.8)));
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 1);
		assertEquals(1, r.size());
		assertTrue(r.get(0).metadata().isEmpty());
	}

	/** 损坏的 map 字节 → 空元数据，不抛。 */
	@Test
	public void testCorruptMapCell() {
		mock.rowsResponder = cql -> mock.rows(new Col[] {
				new Col("id", varcharType()), new Col("text", varcharType()),
				new Col("metadata", mapType()), new Col("sim", doubleType()) },
				row(bytes("v1"), bytes("t"), bytes(new byte[] { 0x7F, 0x7F, 0x7F }), mock.doubleBytes(0.8)));
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 1);
		assertTrue(r.get(0).metadata().isEmpty());
	}

	/** 自动建表：dimension>0 时发 CREATE KEYSPACE/TABLE/INDEX。 */
	@Test
	public void testAutoCreateSchema() {
		baseBuilder().dimension(3).autoCreate(true).build();
		List<String> qs = mock.queries();
		assertTrue(qs.toString(), qs.stream().anyMatch(q -> q.startsWith("CREATE KEYSPACE")));
		assertTrue(qs.toString(), qs.stream().anyMatch(q -> q.startsWith("CREATE TABLE")));
		assertTrue(qs.toString(), qs.stream().anyMatch(q -> q.contains("CREATE CUSTOM INDEX")));
	}

	/** 自动建表缺 dimension → AiException。 */
	@Test
	public void testAutoCreateNoDimensionThrows() {
		assertThrows(AiException.class, () -> baseBuilder().autoCreate(true).build());
	}

	/** 明文 SASL：AUTHENTICATE → AUTH_RESPONSE → AUTH_SUCCESS，业务正常。 */
	@Test
	public void testPlainAuth() {
		mock.authMode = AuthMode.PLAIN;
		baseBuilder().user("u").password("p").build().addAll(List.of(v("v1")));
		assertTrue(mock.lastQuery().startsWith("INSERT"));
	}

	/** 启动期 ERROR 帧 → AiException。 */
	@Test
	public void testStartupErrorThrows() {
		mock.authMode = AuthMode.STARTUP_ERROR;
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 启动期未知 opcode 被忽略后接 READY，握手不中断。 */
	@Test
	public void testStartupUnknownOpcodeIgnored() {
		mock.authMode = AuthMode.UNKNOWN_THEN_READY;
		// 默认响应为 RESULT VOID → size 无行返回 -1（握手已打通）
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 查询后返回非 RESULT opcode → AiException。 */
	@Test
	public void testQueryWrongOpcodeThrows() {
		mock.responder = cql -> mock.frame(0x09, new byte[0]); // 非 RESULT
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** Set_keyspace(kind=3) 结果 → 空结果集。 */
	@Test
	public void testResultSetKeyspace() {
		mock.responder = cql -> mock.rowsFrameBody(3, new Col[0], new byte[][][] {});
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 分页标志 + 无全局表规格：仍正确解析行。 */
	@Test
	public void testPagingAndNoGlobalSpec() {
		mock.rowsResponder = cql -> mock.rowsFlags(new Col[] {
				new Col("c", doubleType()) }, new int[][] { { 7 } }, 0x02);
		assertEquals(7, baseBuilder().build().size());
	}

	/** 列类型覆盖 list/map/set/UDT/tuple/custom：skipTypeOption 全分支。 */
	@Test
	public void testComplexColumnTypes() {
		mock.rowsResponder = cql -> mock.rows(new Col[] {
				new Col("id", varcharType()),
				new Col("list", listType()),
				new Col("map", mapType()),
				new Col("set", setType()),
				new Col("udt", udtType()),
				new Col("tuple", tupleType()),
				new Col("custom", customType()) },
				row(bytes("v1"), bytes("x"), mock.mapBytes(new String[] {}), bytes("x"),
						bytes("x"), bytes("x"), bytes("x")));
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 1);
		assertEquals(1, r.size());
		assertEquals("v1", r.get(0).id());
	}

	// ===== 帧/类型构造辅助 =====

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] bytes(byte[] b) {
		return b;
	}

	private static byte[][] row(byte[]... cells) {
		return cells;
	}

	private static byte[] varcharType() {
		return typeOpt(0x000D);
	}

	private static byte[] doubleType() {
		return typeOpt(0x0007);
	}

	private static byte[] typeOpt(int id) {
		ByteBuffer bb = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
		bb.putShort((short) id);
		return bb.array();
	}

	private static void writeShortBE(ByteArrayOutputStream b, int v) {
		b.write((v >> 8) & 0xFF);
		b.write(v & 0xFF);
	}

	private static byte[] listType() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.writeBytes(typeOpt(0x0020));
		b.writeBytes(typeOpt(0x000D)); // element varchar
		return b.toByteArray();
	}

	private static byte[] mapType() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.writeBytes(typeOpt(0x0021));
		b.writeBytes(typeOpt(0x000D)); // key
		b.writeBytes(typeOpt(0x000D)); // value
		return b.toByteArray();
	}

	private static byte[] setType() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.writeBytes(typeOpt(0x0022));
		b.writeBytes(typeOpt(0x000D));
		return b.toByteArray();
	}

	private static byte[] customType() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.writeBytes(typeOpt(0x0000));
		writeShortBE(b, "org.Custom".length());
		b.writeBytes("org.Custom".getBytes(StandardCharsets.UTF_8));
		return b.toByteArray();
	}

	private static byte[] udtType() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.writeBytes(typeOpt(0x0030));
		writeShortBE(b, "ks".length());
		b.writeBytes("ks".getBytes(StandardCharsets.UTF_8));
		writeShortBE(b, "u".length());
		b.writeBytes("u".getBytes(StandardCharsets.UTF_8));
		writeShortBE(b, 1); // 1 field
		writeShortBE(b, "f".length());
		b.writeBytes("f".getBytes(StandardCharsets.UTF_8));
		b.writeBytes(typeOpt(0x000D));
		return b.toByteArray();
	}

	private static byte[] tupleType() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.writeBytes(typeOpt(0x0031));
		writeShortBE(b, 2); // 2 elements
		b.writeBytes(typeOpt(0x000D));
		b.writeBytes(typeOpt(0x0007));
		return b.toByteArray();
	}

	/** 列描述：列名 + 序列化好的类型 option blob。 */
	static final class Col {
		final String name;
		final byte[] typeOpt;

		Col(String name, byte[] typeOpt) {
			this.name = name;
			this.typeOpt = typeOpt;
		}
	}

	private enum AuthMode {
		READY, PLAIN, STARTUP_ERROR, UNKNOWN_THEN_READY
	}

	/** 最小 CQL v4 mock，支持多态握手与脚本化响应。 */
	static final class MockCassandra {
		private final ServerSocket server;
		private final List<String> queries = new ArrayList<>();
		final AtomicReference<String> last = new AtomicReference<>();
		volatile java.util.function.Function<String, byte[]> responder = c -> voidFrame();
		volatile java.util.function.Function<String, byte[]> rowsResponder;
		volatile AuthMode authMode = AuthMode.READY;

		MockCassandra() throws IOException {
			this.server = new ServerSocket(0);
			Thread t = new Thread(this::serve, "mock-cass-deep");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		List<String> queries() {
			synchronized (queries) {
				return new ArrayList<>(queries);
			}
		}

		String lastQuery() {
			return last.get();
		}

		private void serve() {
			while (!server.isClosed()) {
				Socket s;
				try {
					s = server.accept();
				} catch (IOException e) {
					break;
				}
				try {
					handle(s);
				} catch (IOException ignored) {
					// 单连接关闭/读超时：继续接受下一条短连接。
				}
			}
		}

		private void handle(Socket s) throws IOException {
			s.setSoTimeout(3000);
			DataInputStream in = new DataInputStream(s.getInputStream());
			try {
				readFrame(in); // STARTUP
				switch (authMode) {
					case PLAIN -> {
						writeFrame(s.getOutputStream(), 0x03, longBody("org.apache.cassandra.auth.PasswordAuthenticator"));
						readFrame(in); // AUTH_RESPONSE
						writeFrame(s.getOutputStream(), 0x10, new byte[0]); // AUTH_SUCCESS
					}
					case STARTUP_ERROR -> {
						writeFrame(s.getOutputStream(), 0x00, errBody(8704, "boom"));
						return;
					}
					case UNKNOWN_THEN_READY -> {
						writeFrame(s.getOutputStream(), 0x09, new byte[0]); // 未知 opcode
						writeFrame(s.getOutputStream(), 0x02, new byte[0]); // READY
					}
					default -> writeFrame(s.getOutputStream(), 0x02, new byte[0]); // READY
				}
				while (!s.isClosed()) {
					Frame f = readFrame(in);
					if (f.opcode == -1) {
						break;
					}
					String cql = extractCql(f.body);
					last.set(cql);
					synchronized (queries) {
						queries.add(cql);
					}
					byte[] resp;
					if (rowsResponder != null && cql.trim().startsWith("SELECT")) {
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

		byte[] nullBytes() {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			writeInt(b, -1);
			return b.toByteArray();
		}

		byte[] mapBytes(String[] kv) {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			writeShortBE(b, kv.length / 2);
			for (int i = 0; i + 1 < kv.length; i += 2) {
				writeBytes(b, kv[i]);
				writeBytes(b, kv[i + 1]);
			}
			return b.toByteArray();
		}

		byte[] doubleBytes(double v) {
			return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(v).array();
		}

		byte[] rows(Col[] cols, byte[][]... rows) {
			return rowsFlags(cols, rows, 0x01);
		}

		byte[] rowsFlags(Col[] cols, int[][] intRows, int flags) {
			// 便捷重载：把 intRows 当单列 double
			byte[][][] rows = new byte[intRows.length][1][];
			for (int i = 0; i < intRows.length; i++) {
				rows[i][0] = doubleBytes(intRows[i][0]);
			}
			return rowsFlags(cols, rows, flags);
		}

		byte[] rowsFlags(Col[] cols, byte[][][] rows, int flags) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			writeInt(body, 0x0002); // Rows
			writeInt(body, flags);
			writeInt(body, cols.length);
			if ((flags & 0x02) != 0) {
				// paging_state：int 长度 + 内容
				writeInt(body, 4);
				body.write(new byte[] { 1, 2, 3, 4 }, 0, 4);
			}
			if ((flags & 0x01) != 0) {
				writeString(body, "testks");
				writeString(body, "docs");
			}
			for (Col c : cols) {
				writeString(body, c.name);
				body.write(c.typeOpt, 0, c.typeOpt.length);
			}
			writeInt(body, rows.length);
			for (byte[][] row : rows) {
				for (byte[] cell : row) {
					if (cell == null) {
						writeInt(body, -1);
					} else {
						writeInt(body, cell.length);
						body.write(cell, 0, cell.length);
					}
				}
			}
			return frame(0x08, body.toByteArray());
		}

		byte[] rowsFrameBody(int kind, Col[] cols, byte[][][] rows) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			writeInt(body, kind);
			if (kind == 2) {
				writeInt(body, 0x01);
				writeInt(body, cols.length);
				writeString(body, "testks");
				writeString(body, "docs");
				for (Col c : cols) {
					writeString(body, c.name);
					body.write(c.typeOpt, 0, c.typeOpt.length);
				}
				writeInt(body, rows.length);
			}
			return frame(0x08, body.toByteArray());
		}

		private static byte[] voidFrame() {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			writeInt(body, 0x0001);
			return frameStatic(0x08, body.toByteArray());
		}

		byte[] frame(int opcode, byte[] body) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(0x84);
			out.write(0x00);
			out.write(0x00);
			out.write(0x00);
			out.write(opcode);
			writeInt(out, body.length);
			out.write(body, 0, body.length);
			return out.toByteArray();
		}

		private static byte[] longBody(String s) {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			writeShort(b, s.getBytes(StandardCharsets.UTF_8).length);
			b.writeBytes(s.getBytes(StandardCharsets.UTF_8));
			return b.toByteArray();
		}

		private static byte[] errBody(int code, String msg) {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			writeInt(b, code);
			writeString(b, msg);
			return b.toByteArray();
		}

		private static void writeFrame(java.io.OutputStream out, int opcode, byte[] body) throws IOException {
			out.write(frameStatic(opcode, body));
			out.flush();
		}

		private static byte[] frameStatic(int opcode, byte[] body) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(0x84);
			out.write(0);
			out.write(0);
			out.write(0);
			out.write(opcode);
			writeInt(out, body.length);
			out.write(body, 0, body.length);
			return out.toByteArray();
		}

		private static Frame readFrame(DataInputStream in) throws IOException {
			in.readUnsignedByte();
			in.readUnsignedByte();
			in.readShort();
			int opcode = in.readUnsignedByte();
			int len = in.readInt();
			byte[] body = new byte[len];
			in.readFully(body);
			return new Frame(opcode, body);
		}

		private static void writeInt(ByteArrayOutputStream b, int v) {
			b.write((v >> 24) & 0xFF);
			b.write((v >> 16) & 0xFF);
			b.write((v >> 8) & 0xFF);
			b.write(v & 0xFF);
		}

		private static void writeShort(ByteArrayOutputStream b, int v) {
			b.write((v >> 8) & 0xFF);
			b.write(v & 0xFF);
		}

		private static void writeString(ByteArrayOutputStream b, String s) {
			byte[] x = s.getBytes(StandardCharsets.UTF_8);
			writeShort(b, x.length);
			b.write(x, 0, x.length);
		}

		private static void writeBytes(ByteArrayOutputStream b, String s) {
			byte[] x = s.getBytes(StandardCharsets.UTF_8);
			writeInt(b, x.length);
			b.write(x, 0, x.length);
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
		final int opcode;
		final byte[] body;

		Frame(int opcode, byte[] body) {
			this.opcode = opcode;
			this.body = body;
		}
	}
}
