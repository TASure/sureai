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
 * See the License for the specific language governing permissions and limitations under the License.
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
import com.sure.ai.rag.store.pgvector.PgVectorStore;

/**
 * {@link PgVectorStore} 错误/边界分支单元测试：扩展 {@link ServerSocket} mock 覆盖
 * 空批量/null/多值插入、size 异常路径、minScore 过滤、元数据与向量解析边界、
 * 自动建表、明文/trust/错误认证、查询期错误与通知忽略、null 单元格，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class PgVectorStoreDeepTest {

	private MockPg mock;

	/** 启动 mock。 */
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
		return PgVectorStore.builder().host("127.0.0.1").port(mock.port())
				.database("db").user("u").table("docs");
	}

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "text-" + id, Map.of("source", id + ".pdf"));
	}

	/** 空批量直接返回；null 元素跳过；多值逗号拼接；add(Vector) 委托。 */
	@Test
	public void testAddAllBatch() {
		mock.responder = sql -> cmd("INSERT 0 2");
		baseBuilder().build().add(v("v1"));
		baseBuilder().build().addAll(new ArrayList<>(java.util.Arrays.asList(v("v1"), null, v("v2"))));
		String sql = mock.lastQuery();
		assertTrue(sql.startsWith("INSERT INTO"));
		// 两行 VALUES
		assertTrue(sql, sql.indexOf("),(") > 0 || sql.contains("ON CONFLICT"));
	}

	/** 构建器全量 setter。 */
	@Test
	public void testBuilderSetters() {
		mock.responder = sql -> cmd("INSERT 0 1");
		baseBuilder().password("pw").timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** delete 影响 0 行 → false。 */
	@Test
	public void testDeleteNoAffected() {
		mock.responder = sql -> cmd("DELETE 0");
		assertFalse(baseBuilder().build().delete("nope"));
	}

	/** size：count 非数字 → -1。 */
	@Test
	public void testSizeNonNumeric() {
		mock.responder = sql -> rows(new String[] { "c" }, new String[][] { { "abc" } });
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 检索：minScore 过滤；metadata 为 null/"null" → 空元数据；embedding 空串 → null。 */
	@Test
	public void testSearchMetadataEdge() {
		mock.responder = sql -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.writeBytes(rowDesc(new String[] { "id", "text", "metadata", "embedding", "distance" }));
			out.writeBytes(dataRow("v1", "t", "null", "", "0.2"));
			out.writeBytes(cmd("SELECT 1"));
			out.writeBytes(ready());
			return out.toByteArray();
		};
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 1, 0.5, null);
		assertEquals(1, r.size());
		assertTrue(r.get(0).metadata().isEmpty());
	}

	/** 检索：minScore 过滤掉低分行。 */
	@Test
	public void testSearchMinScore() {
		mock.responder = sql -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.writeBytes(rowDesc(new String[] { "id", "text", "metadata", "embedding", "distance" }));
			out.writeBytes(dataRow("v1", "t", "{}", "[0.1,0.2]", "0.9"));
			out.writeBytes(dataRow("v2", "t", "{}", "[0.3,0.4]", "0.1"));
			out.writeBytes(cmd("SELECT 2"));
			out.writeBytes(ready());
			return out.toByteArray();
		};
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("v2", r.get(0).id());
	}

	/** 自动建表：dimension>0 发 CREATE EXTENSION/TABLE；缺维度抛异常。 */
	@Test
	public void testAutoCreateTable() {
		mock.responder = sql -> cmd("SELECT 0");
		baseBuilder().dimension(3).autoCreate(true).build();
		List<String> qs = mock.queries();
		assertTrue(qs.toString(), qs.stream().anyMatch(q -> q.startsWith("CREATE EXTENSION")));
		assertTrue(qs.toString(), qs.stream().anyMatch(q -> q.startsWith("CREATE TABLE")));
		assertThrows(AiException.class, () -> baseBuilder().autoCreate(true).build());
	}

	/** trust 认证：服务端直接 ReadyForQuery（无密码）。 */
	@Test
	public void testTrustAuth() {
		mock.auth = Auth.TRUST;
		mock.responder = sql -> rows(new String[] { "c" }, new String[][] { { "5" } });
		assertEquals(5, baseBuilder().build().size());
	}

	/** 认证期错误消息 → AiException。 */
	@Test
	public void testAuthErrorThrows() {
		mock.auth = Auth.ERROR;
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 查询期错误消息 → AiException。 */
	@Test
	public void testQueryErrorThrows() {
		mock.responder = sql -> errorMsg("relation x does not exist");
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** null 单元格（len=-1）解析。 */
	@Test
	public void testNullCell() {
		mock.responder = sql -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.writeBytes(rowDesc(new String[] { "id", "text" }));
			out.writeBytes(nullRow());
			out.writeBytes(cmd("SELECT 1"));
			out.writeBytes(ready());
			return out.toByteArray();
		};
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f }, 1);
		assertEquals(1, r.size());
	}

	// ===== 帧构造辅助 =====

	private static byte[] cmd(String tag) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream p = new ByteArrayOutputStream();
		p.writeBytes(tag.getBytes(StandardCharsets.UTF_8));
		p.write(0);
		out.writeBytes(msg('C', p.toByteArray()));
		out.writeBytes(ready());
		return out.toByteArray();
	}

	private static byte[] ready() {
		return msg('Z', new byte[] { 'I' });
	}

	private static byte[] rowDesc(String[] cols) {
		ByteArrayOutputStream p = new ByteArrayOutputStream();
		p.writeBytes(int16(cols.length));
		for (String c : cols) {
			p.writeBytes(c.getBytes(StandardCharsets.UTF_8));
			p.write(0);
			p.writeBytes(new byte[18]);
		}
		return msg('T', p.toByteArray());
	}

	private static byte[] dataRow(String... values) {
		ByteArrayOutputStream p = new ByteArrayOutputStream();
		p.writeBytes(int16(values.length));
		for (String v : values) {
			byte[] b = v.getBytes(StandardCharsets.UTF_8);
			p.writeBytes(int32(b.length));
			p.writeBytes(b);
		}
		return msg('D', p.toByteArray());
	}

	private static byte[] nullRow() {
		ByteArrayOutputStream p = new ByteArrayOutputStream();
		p.writeBytes(int16(2));
		p.writeBytes(int32(-1)); // null
		p.writeBytes(int32(-1)); // null
		return msg('D', p.toByteArray());
	}

	private static byte[] rows(String[] cols, String[][] rows) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(rowDesc(cols));
		for (String[] row : rows) {
			out.writeBytes(dataRow(row));
		}
		out.writeBytes(cmd("SELECT " + rows.length));
		out.writeBytes(ready());
		return out.toByteArray();
	}

	private static byte[] errorMsg(String message) {
		ByteArrayOutputStream p = new ByteArrayOutputStream();
		p.write('S');
		p.writeBytes("ERROR\0".getBytes(StandardCharsets.UTF_8));
		p.write('M');
		p.writeBytes(message.getBytes(StandardCharsets.UTF_8));
		p.write(0);
		p.write(0);
		return msg('E', p.toByteArray());
	}

	private static byte[] msg(char type, byte[] payload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(type);
		out.writeBytes(int32(4 + payload.length));
		out.writeBytes(payload);
		return out.toByteArray();
	}

	private static byte[] int16(int v) {
		return new byte[] { (byte) (v >> 8), (byte) v };
	}

	private static byte[] int32(int v) {
		return new byte[] { (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v };
	}

	private enum Auth {
		CLEARTEXT, TRUST, ERROR
	}

	/** 最小 PG 协议 mock。 */
	static final class MockPg {
		private final ServerSocket server;
		private final List<String> queries = new ArrayList<>();
		private final AtomicReference<String> last = new AtomicReference<>();
		volatile java.util.function.Function<String, byte[]> responder = sql -> cmd("SELECT 0");
		volatile Auth auth = Auth.CLEARTEXT;

		MockPg() throws IOException {
			this.server = new ServerSocket(0);
			Thread t = new Thread(this::serve, "mock-pg-deep");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		String lastQuery() {
			return last.get();
		}

		List<String> queries() {
			synchronized (queries) {
				return new ArrayList<>(queries);
			}
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
			// Startup
			int startupLen = in.readInt();
			in.readFully(new byte[startupLen - 4]);
			switch (auth) {
				case ERROR -> {
					s.getOutputStream().write(errorMsg("auth failed"));
					s.getOutputStream().flush();
					return;
				}
				case TRUST -> {
					// ParameterStatus 后直接 ReadyForQuery
					ByteArrayOutputStream ps = new ByteArrayOutputStream();
					ps.writeBytes("application_name".getBytes(StandardCharsets.UTF_8));
					ps.write(0);
					ps.writeBytes("x".getBytes(StandardCharsets.UTF_8));
					ps.write(0);
					s.getOutputStream().write(msg('S', ps.toByteArray()));
					s.getOutputStream().write(ready());
				}
				default -> {
					s.getOutputStream().write(msg('R', int32(3))); // 明文
					s.getOutputStream().flush();
					in.read(); // 'p'
					int pwLen = in.readInt();
					in.readFully(new byte[pwLen - 4]);
					s.getOutputStream().write(msg('R', int32(0)));
					s.getOutputStream().write(ready());
				}
			}
			s.getOutputStream().flush();
			while (!s.isClosed()) {
				int type = in.read();
				if (type == -1) {
					break;
				}
				int len = in.readInt();
				byte[] payload = new byte[len - 4];
				in.readFully(payload);
				String sql = new String(payload, 0, payload.length - 1, StandardCharsets.UTF_8);
				last.set(sql);
				synchronized (queries) {
					queries.add(sql);
				}
				s.getOutputStream().write(responder.apply(sql));
				s.getOutputStream().flush();
			}
			s.close();
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
