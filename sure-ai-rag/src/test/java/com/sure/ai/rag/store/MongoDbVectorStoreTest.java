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
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.mongodb.Bson;
import com.sure.ai.rag.store.mongodb.MongoDbVectorStore;

/**
 * {@link MongoDbVectorStore} 单元测试：本地 {@link ServerSocket} mock OP_MSG/BSON，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class MongoDbVectorStoreTest {

	private static final String COLL = "docs";

	private MockMongo mock;

	/** 启动本地 mock。 */
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
				.database("testdb").collection(COLL).vectorIndex("vec_idx");
	}

	private static Vector sampleVector(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f, 0.3f }, "text-" + id,
				Map.of("source", id + ".pdf"));
	}

	/** addAll：命令体含 update/upsert 字样。 */
	@Test
	public void testAddAll() {
		baseBuilder().build().addAll(List.of(sampleVector("v1")));
		String req = mock.lastRequest();
		assertTrue(req, req.contains("update"));
		assertTrue(req, req.contains("_id"));
	}

	/** 检索：命令体含 aggregate/$vectorSearch，解析 firstBatch 还原 score。 */
	@Test
	public void testSearch() {
		mock.responder = cmd -> new Bson.Doc()
				.addDouble("ok", 1.0)
				.addDoc("cursor", new Bson.Doc().addArray("firstBatch", new Bson.Doc()
						.addDoc("0", new Bson.Doc()
								.addString("_id", "v1")
								.addString("text", "hello")
								.addDoc("metadata", new Bson.Doc().addString("source", "a.pdf"))
								.addDouble("score", 0.8))
						.addDoc("1", new Bson.Doc()
								.addString("_id", "v2")
								.addString("text", "world")
								.addDoc("metadata", new Bson.Doc())
								.addDouble("score", 0.9))));
		List<SimilaritySearchResult> results = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 2);
		String req = mock.lastRequest();
		assertTrue(req, req.contains("aggregate"));
		assertTrue(req, req.contains("$vectorSearch"));
		assertEquals(2, results.size());
		assertEquals("v2", results.get(0).id());
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("v1", results.get(1).id());
		assertEquals(0.8, results.get(1).score(), 1e-9);
		assertEquals("a.pdf", results.get(1).metadata().get("source"));
	}

	/** 带 filter：命令体含 filter 谓词字段。 */
	@Test
	public void testSearchWithFilter() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 1.0)
				.addDoc("cursor", new Bson.Doc().addArray("firstBatch", new Bson.Doc()));
		baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 1,
				FilterExpression.eq("tenant", "acme"));
		String req = mock.lastRequest();
		assertTrue(req, req.contains("filter"));
		assertTrue(req, req.contains("tenant"));
	}

	/** COUNT：n 字段解析。 */
	@Test
	public void testSize() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 1.0).addInt32("n", 7);
		assertEquals(7, baseBuilder().build().size());
	}

	/** ok=0/code=13 抛明确 SCRAM 异常。 */
	@Test
	public void testUnauthorizedThrows() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 0.0).addInt32("code", 13)
				.addString("errmsg", "unauthorized");
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** ok=0 其他错误抛 AiException。 */
	@Test
	public void testErrorThrows() {
		mock.responder = cmd -> new Bson.Doc().addDouble("ok", 0.0).addInt32("code", 2)
				.addString("errmsg", "unauthorized: bad data");
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 最小 OP_MSG mock ServerSocket。 */
	static final class MockMongo {
		private final ServerSocket server;
		private final AtomicReference<String> last = new AtomicReference<>();
		volatile java.util.function.Function<byte[], Bson.Doc> responder = cmd -> new Bson.Doc()
				.addDouble("ok", 1.0);

		MockMongo() throws IOException {
			this.server = new ServerSocket(0);
			Thread t = new Thread(this::serve, "mock-mongo");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		String lastRequest() {
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
				// 第一条：hello
				byte[] helloBody = readOpMsg(in);
				writeOpMsg(s, new Bson.Doc().addDouble("ok", 1.0).addInt32("maxWireVersion", 17));
				// 第二条：业务命令
				byte[] cmd = readOpMsg(in);
				last.set(new String(cmd, StandardCharsets.UTF_8));
				writeOpMsg(s, responder.apply(cmd));
			} finally {
				s.close();
			}
		}

		private static byte[] readOpMsg(DataInputStream in) throws IOException {
			byte[] header = readN(in, 16);
			ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			int totalLen = hb.getInt();
			readN(in, 4); // flagBits
			in.read(); // section kind
			return readN(in, totalLen - 16 - 4 - 1);
		}

		private static void writeOpMsg(Socket s, Bson.Doc doc) throws IOException {
			byte[] body = doc.encode();
			int totalLen = 16 + 4 + 1 + body.length;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeIntLE(out, totalLen);
			writeIntLE(out, 1); // requestId
			writeIntLE(out, 0); // responseTo
			writeIntLE(out, 2011); // opCode OP_MSG
			writeIntLE(out, 0); // flagBits
			out.write(0); // section kind 0
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
}
