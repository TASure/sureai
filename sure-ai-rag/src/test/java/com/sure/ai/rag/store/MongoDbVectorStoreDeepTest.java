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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.mongodb.Bson;
import com.sure.ai.rag.store.mongodb.MongoDbVectorStore;

/**
 * {@link MongoDbVectorStore} 错误/边界分支单元测试：扩展 {@link ServerSocket} mock 覆盖
 * 空批量/null、delete/clear 计数、cursor/firstBatch/doc 畸形、minScore 过滤、自动建索引吞错、
 * 错误 opcode、ok 非数字，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class MongoDbVectorStoreDeepTest {

	private MockMongo mock;

	/** 启动 mock。 */
	@Before
	public void setUp() throws IOException {
		this.mock = new MockMongo();
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.mock.close();
	}

	private MongoDbVectorStore.Builder baseBuilder() {
		return MongoDbVectorStore.builder().host("127.0.0.1").port(mock.port())
				.database("db").collection("docs").vectorIndex("vec_idx");
	}

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "text-" + id, Map.of("source", id + ".pdf"));
	}

	/** 空批量直接返回；add(Vector) 委托。 */
	@Test
	public void testAddAllEmpty() {
		baseBuilder().build().addAll(List.of());
		baseBuilder().build().add(v("v1"));
	}

	/** 构建器全量 setter。 */
	@Test
	public void testBuilderSetters() {
		baseBuilder().timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** delete：n>0 true；n<=0 false。 */
	@Test
	public void testDelete() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 1.0).addInt32("n", 1);
		assertTrue(baseBuilder().build().delete("v1"));
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 1.0).addInt32("n", 0);
		assertEquals(false, baseBuilder().build().delete("nope"));
	}

	/** clear：发送 delete 命令。 */
	@Test
	public void testClear() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 1.0);
		baseBuilder().build().clear();
		assertTrue(mock.lastRequest().contains("delete"));
	}

	/** size：n 非数字 → -1。 */
	@Test
	public void testSizeNonNumber() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 1.0).addString("n", "oops");
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 检索：cursor 非 Map / firstBatch 缺失 → 空结果。 */
	@Test
	public void testSearchNoCursor() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 1.0);
		assertTrue(baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 3).isEmpty());
	}

	/** 检索：minScore 过滤；text null 置空串；metadata 映射。 */
	@Test
	public void testSearchRowsEdge() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 1.0)
				.addDoc("cursor", new Bson.Doc().addArray("firstBatch", new Bson.Doc()
						.addDoc("0", new Bson.Doc().addString("_id", "low").addDouble("score", 0.1))
						.addDoc("1", new Bson.Doc().addString("_id", "high").addString("text", "keep")
								.addDoc("metadata", new Bson.Doc().addString("source", "a.pdf"))
								.addDouble("score", 0.9))));
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("high", r.get(0).id());
		assertEquals("a.pdf", r.get(0).metadata().get("source"));
	}

	/** 自动建索引：dimension>0 best-effort（吞服务端错误）；缺维度抛异常。 */
	@Test
	public void testAutoCreateIndex() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 0.0).addInt32("code", 2).addString("errmsg", "no");
		baseBuilder().dimension(3).autoCreate(true).build();
		assertThrows(AiException.class, () -> baseBuilder().autoCreate(true).build());
	}

	/** 服务端返回错误 opcode → AiException。 */
	@Test
	public void testWrongOpcodeThrows() {
		mock.opCode = 2010; // 非 OP_MSG
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** ok 字段缺失/非数字 → AiException。 */
	@Test
	public void testOkMissingThrows() {
		mock.responder = cmd -> new Bson.Doc().addString("ok", "nope");
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 最小 OP_MSG mock，支持多短连接与脚本化响应。 */
	static final class MockMongo {
		private final ServerSocket server;
		final AtomicRequest last = new AtomicRequest();
		volatile java.util.function.Function<byte[], Bson.Doc> responder = cmd -> new Bson.Doc()
				.addDouble("ok", 1.0);
		volatile int opCode = 2011;

		MockMongo() throws IOException {
			this.server = new ServerSocket(0);
			Thread t = new Thread(this::serve, "mock-mongo-deep");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		String lastRequest() {
			return last.value;
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
					// 单连接关闭，继续。
				}
			}
		}

		private void handle(Socket s) throws IOException {
			s.setSoTimeout(3000);
			DataInputStream in = new DataInputStream(s.getInputStream());
			byte[] helloBody = readOpMsg(in);
			writeOpMsg(s, new Bson.Doc().addDouble("ok", 1.0).addInt32("maxWireVersion", 17), 2011);
			byte[] cmd = readOpMsg(in);
			last.value = new String(cmd, StandardCharsets.UTF_8);
			writeOpMsg(s, responder.apply(cmd), opCode);
			s.close();
		}

		private static byte[] readOpMsg(DataInputStream in) throws IOException {
			byte[] header = readN(in, 16);
			ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			int totalLen = hb.getInt();
			readN(in, 4);
			in.read();
			return readN(in, totalLen - 16 - 4 - 1);
		}

		private void writeOpMsg(Socket s, Bson.Doc doc, int code) throws IOException {
			byte[] body = doc.encode();
			int totalLen = 16 + 4 + 1 + body.length;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeIntLE(out, totalLen);
			writeIntLE(out, 1);
			writeIntLE(out, 0);
			writeIntLE(out, code);
			writeIntLE(out, 0);
			out.write(0);
			out.write(body);
			s.getOutputStream().write(out.toByteArray());
			s.getOutputStream().flush();
		}

		private static byte[] readN(DataInputStream in, int n) throws IOException {
			byte[] buf = new byte[n];
			in.readFully(buf);
			return buf;
		}

		private static void writeIntLE(ByteArrayOutputStream out, int v) {
			out.write(v & 0xFF);
			out.write((v >> 8) & 0xFF);
			out.write((v >> 16) & 0xFF);
			out.write((v >> 24) & 0xFF);
		}

		void close() {
			try {
				server.close();
			} catch (IOException ignored) {
				// ignore
			}
		}
	}

	/** 可变引用持有者。 */
	static final class AtomicRequest {
		String value = "";
	}
}
