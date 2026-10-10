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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.pgvector.PgVectorStore;

/**
 * {@link PgVectorStore} 单元测试：本地 {@link ServerSocket} mock PostgreSQL 简单查询协议，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class PgVectorStoreTest {

	private static final String TABLE = "docs";

	private MockPg mock;

	/** 启动本地 mock。 */
	@Before
	public void setUp() throws IOException {
		this.mock = new MockPg();
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.mock.close();
	}

	private PgVectorStore.Builder baseBuilder() {
		return PgVectorStore.builder()
				.host("127.0.0.1").port(mock.port())
				.database("testdb").user("u").table(TABLE);
	}

	private static Vector sampleVector(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f, 0.3f }, "text-" + id,
				Map.of("source", id + ".pdf"));
	}

	/** INSERT：SQL 含表名、vector 字面量、jsonb 元数据、ON CONFLICT。 */
	@Test
	public void testAddAll() {
		mock.responder(sql -> MockPg.concat(MockPg.commandComplete("INSERT 0 1"), MockPg.readyForQuery()));
		baseBuilder().build().addAll(List.of(sampleVector("v1")));
		String sql = mock.lastQuery();
		assertTrue(sql.startsWith("INSERT INTO"));
		assertTrue(sql.contains("ON CONFLICT (id)"));
		assertTrue(sql.contains("::vector"));
		assertTrue(sql.contains("::jsonb"));
		assertTrue(sql.contains("'v1'"));
	}

	/** SELECT 检索：解析 RowDescription/DataRow，距离还原为余弦相似度降序。 */
	@Test
	public void testSearch() {
		mock.responder(sql -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.writeBytes(rowDescription(new String[] { "id", "text", "metadata", "embedding", "distance" }));
			out.writeBytes(dataRow(new String[] { "v1", "hello", "{\"source\":\"a.pdf\"}", "[0.1,0.2,0.3]", "0.2" }));
			out.writeBytes(dataRow(new String[] { "v2", "world", "{}", "[0.4,0.5,0.6]", "0.1" }));
			out.writeBytes(MockPg.commandComplete("SELECT 2"));
			out.writeBytes(MockPg.readyForQuery());
			return out.toByteArray();
		});
		List<SimilaritySearchResult> results = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 2);
		String sql = mock.lastQuery();
		assertTrue(sql.contains("ORDER BY embedding <=>"));
		assertTrue(sql.contains("LIMIT 2"));
		assertEquals(2, results.size());
		// v2 distance 0.1 -> score 0.9；v1 distance 0.2 -> score 0.8；降序 v2 在前
		assertEquals("v2", results.get(0).id());
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("world", results.get(0).text());
		assertEquals("v1", results.get(1).id());
		assertEquals(0.8, results.get(1).score(), 1e-9);
		assertEquals("a.pdf", results.get(1).metadata().get("source"));
	}

	/** 带 filter：SQL WHERE 含 metadata->>'tenant'。 */
	@Test
	public void testSearchWithFilter() {
		mock.responder(sql -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.writeBytes(MockPg.commandComplete("SELECT 0"));
			out.writeBytes(MockPg.readyForQuery());
			return out.toByteArray();
		});
		baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 1,
				FilterExpression.eq("tenant", "acme"));
		String sql = mock.lastQuery();
		assertTrue(sql, sql.contains("WHERE metadata->>'tenant' = 'acme'"));
	}

	/** DELETE：SQL 含 WHERE id，tag DELETE 1 返回 true；DELETE 0 返回 false。 */
	@Test
	public void testDelete() {
		mock.responder(sql -> MockPg.concat(MockPg.commandComplete("DELETE 1"), MockPg.readyForQuery()));
		assertTrue(baseBuilder().build().delete("v1"));
		assertTrue(mock.lastQuery().contains("DELETE FROM"));
		assertTrue(mock.lastQuery().contains("WHERE id = 'v1'"));

		mock.responder(sql -> MockPg.concat(MockPg.commandComplete("DELETE 0"), MockPg.readyForQuery()));
		assertFalse(baseBuilder().build().delete("nope"));
	}

	/** TRUNCATE：clear SQL。 */
	@Test
	public void testClear() {
		mock.responder(sql -> MockPg.concat(MockPg.commandComplete("TRUNCATE TABLE 0"), MockPg.readyForQuery()));
		baseBuilder().build().clear();
		assertTrue(mock.lastQuery().startsWith("TRUNCATE TABLE"));
	}

	/** COUNT(*)：size 解析首行。 */
	@Test
	public void testSize() {
		mock.responder(sql -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.writeBytes(rowDescription(new String[] { "c" }));
			out.writeBytes(dataRow(new String[] { "7" }));
			out.writeBytes(MockPg.commandComplete("SELECT 1"));
			out.writeBytes(MockPg.readyForQuery());
			return out.toByteArray();
		});
		assertEquals(7, baseBuilder().build().size());
	}

	/** 服务端错误响应包装为 AiException。 */
	@Test
	public void testErrorResponseThrows() {
		mock.responder(sql -> MockPg.errorResponse("relation \"nope\" does not exist"));
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 不支持的认证方式（SASL）抛明确异常。 */
	@Test
	public void testUnsupportedAuthThrows() {
		mock.forceSasl();
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 连接拒绝包装为 AiException。 */
	@Test
	public void testConnectionRefusedThrows() throws IOException {
		// 取一个立即关闭的临时端口，避免复用本类 mock 端口被其他并发测试重新占用。
		try (java.net.ServerSocket dead = new java.net.ServerSocket(0)) {
			int deadPort = dead.getLocalPort();
			mock.close();
			PgVectorStore store = PgVectorStore.builder()
					.host("127.0.0.1").port(deadPort)
					.database("testdb").user("u").table(TABLE).build();
			assertThrows(AiException.class, store::size);
		}
	}

	// ===== 协议帧构造辅助 =====

	private static byte[] rowDescription(String[] names) {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.writeBytes(int16(names.length));
		for (String n : names) {
			payload.writeBytes(n.getBytes(StandardCharsets.UTF_8));
			payload.write(0);
			payload.writeBytes(new byte[18]); // 字段元信息跳过
		}
		return MockPg.msg('T', payload.toByteArray());
	}

	private static byte[] dataRow(String[] values) {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.writeBytes(int16(values.length));
		for (String v : values) {
			byte[] b = v.getBytes(StandardCharsets.UTF_8);
			payload.writeBytes(int32(b.length));
			payload.writeBytes(b);
		}
		return MockPg.msg('D', payload.toByteArray());
	}

	private static byte[] int16(int v) {
		return new byte[] { (byte) (v >> 8), (byte) v };
	}

	private static byte[] int32(int v) {
		return new byte[] { (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v };
	}

	/**
	 * 最小 PostgreSQL 协议 mock ServerSocket：握手（Startup -> 明文认证 -> ReadyForQuery），
	 * 随后每收到一条 Query 就回写脚本化响应帧。
	 */
	static final class MockPg {

		private final ServerSocket server;
		private final List<String> queries = new ArrayList<>();
		private final AtomicReference<String> last = new AtomicReference<>();
		private volatile java.util.function.Function<String, byte[]> responder =
				sql -> concat(commandComplete("SELECT 0"), readyForQuery());
		private volatile boolean sasl;

		MockPg() throws IOException {
			this.server = new ServerSocket(0);
			Thread t = new Thread(this::serveLoop, "mock-pg");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		void responder(java.util.function.Function<String, byte[]> r) {
			this.responder = r;
		}

		void forceSasl() {
			this.sasl = true;
		}

		String lastQuery() {
			return last.get();
		}

		private void serveLoop() {
			try {
				while (!server.isClosed()) {
					Socket s = server.accept();
					handleConnection(s);
				}
			} catch (IOException e) {
				// 服务关闭。
			}
		}

		private void handleConnection(Socket s) throws IOException {
			s.setSoTimeout(3000);
			DataInputStream in = new DataInputStream(s.getInputStream());
			try {
				// 1. StartupMessage（无类型字节）：读长度后整体丢弃
				int startupLen = in.readInt();
				in.readFully(new byte[startupLen - 4]);
				// 2. 认证请求
				if (sasl) {
					s.getOutputStream().write(msg('R', int32(10))); // AuthenticationSASL
					s.getOutputStream().flush();
					return;
				}
				s.getOutputStream().write(msg('R', int32(3))); // 明文密码
				s.getOutputStream().flush();
				// 3. 读 PasswordMessage
				in.read(); // 'p'
				int pwLen = in.readInt();
				in.readFully(new byte[pwLen - 4]);
				// 4. AuthenticationOk + ReadyForQuery
				s.getOutputStream().write(msg('R', int32(0)));
				s.getOutputStream().write(readyForQuery());
				s.getOutputStream().flush();
				// 5. 循环处理 Query
				while (!s.isClosed()) {
					int type = in.read();
					if (type == -1) {
						break;
					}
					int len = in.readInt();
					byte[] payload = new byte[len - 4];
					in.readFully(payload);
					// payload 以 NUL 结尾
					String sql = new String(payload, 0, payload.length - 1, StandardCharsets.UTF_8);
					last.set(sql);
					synchronized (queries) {
						queries.add(sql);
					}
					byte[] resp = responder.apply(sql);
					s.getOutputStream().write(resp);
					s.getOutputStream().flush();
				}
			} finally {
				s.close();
			}
		}

		private static byte[] msg(char type, byte[] payload) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(type);
			out.writeBytes(int32(4 + payload.length));
			out.writeBytes(payload);
			return out.toByteArray();
		}

		private static byte[] readyForQuery() {
			return msg('Z', new byte[] { 'I' });
		}

		private static byte[] concat(byte[]... parts) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (byte[] p : parts) {
				out.writeBytes(p);
			}
			return out.toByteArray();
		}

		private static byte[] commandComplete(String tag) {
			ByteArrayOutputStream payload = new ByteArrayOutputStream();
			payload.writeBytes(tag.getBytes(StandardCharsets.UTF_8));
			payload.write(0);
			return msg('C', payload.toByteArray());
		}

		private static byte[] errorResponse(String message) {
			ByteArrayOutputStream payload = new ByteArrayOutputStream();
			payload.write('S');
			payload.writeBytes("ERROR\0".getBytes(StandardCharsets.UTF_8));
			payload.write('M');
			payload.writeBytes(message.getBytes(StandardCharsets.UTF_8));
			payload.write(0);
			payload.write(0);
			return msg('E', payload.toByteArray());
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
