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
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.filter.FilterExpression;

/**
 * {@link RedisVectorStore} 单元测试：本地 {@link ServerSocket} mock RESP，零真实网络。
 *
 * @author sureai
 * @since 1.8.0
 */
public class RedisVectorStoreTest {

	private static final String INDEX = "idx";

	private MockRedis mock;

	/** 启动本地 mock。 */
	@Before
	public void setUp() throws IOException {
		this.mock = new MockRedis();
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.mock.close();
	}

	private RedisVectorStore.Builder baseBuilder() {
		return RedisVectorStore.builder().host("127.0.0.1").port(mock.port()).indexName(INDEX).dimension(3);
	}

	private static Vector sampleVector(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f, 0.3f }, "text-" + id,
				Map.of("source", id + ".pdf"));
	}

	/** HSET 命令：断言字节格式 *n/$len、字段顺序（blob 为二进制，不断言其解码长度）。 */
	@Test
	public void testAddAll() {
		mock.enqueue("+OK\r\n");
		baseBuilder().build().addAll(List.of(sampleVector("v1")));
		List<String> cmd = mock.lastCommand();
		assertEquals("HSET", cmd.get(0));
		assertEquals("doc:v1", cmd.get(1));
		assertEquals("vector", cmd.get(2));
		assertEquals("text", cmd.get(4));
		assertEquals("text-v1", cmd.get(5));
		assertEquals("source", cmd.get(6));
		assertEquals("v1.pdf", cmd.get(7));
	}

	/** add(Vector) 委托。 */
	@Test
	public void testAddSingle() {
		mock.enqueue("+OK\r\n");
		baseBuilder().build().add(sampleVector("v1"));
	}

	/** 构建器全量 setter。 */
	@Test
	public void testBuilderSetters() {
		mock.enqueue("+OK\r\n");
		baseBuilder().keyPrefix("pre:").vectorField("vec").textField("txt")
				.algo(RedisVectorStore.VectorAlgo.HNSW)
				.timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** HSET 命令总数 = 8（HSET,key,vec,blob,text,textVal,source,metaVal）。 */
	@Test
	public void testHsetArgCount() {
		mock.enqueue("+OK\r\n");
		baseBuilder().build().addAll(List.of(sampleVector("v1")));
		assertEquals(8, mock.lastCommand().size());
	}

	/** FT.SEARCH：解析结果数、key/字段数组，距离还原为余弦相似度降序。 */
	@Test
	public void testSearch() {
		// *3 :2 $6 doc:v1 *6 $14 __vector_score $3 0.2 $4 text $5 hello $6 source $3 a.pdf
		// $6 doc:v2 *4 $14 __vector_score $3 0.1 $4 text $5 world
		String reply = "*5\r\n:2\r\n"
				+ "$6\r\ndoc:v1\r\n"
				+ "*6\r\n$14\r\n__vector_score\r\n$3\r\n0.2\r\n$4\r\ntext\r\n$5\r\nhello\r\n$6\r\nsource\r\n$5\r\na.pdf\r\n"
				+ "$6\r\ndoc:v2\r\n"
				+ "*4\r\n$14\r\n__vector_score\r\n$3\r\n0.1\r\n$4\r\ntext\r\n$5\r\nworld\r\n";
		mock.enqueue(reply);
		List<SimilaritySearchResult> results = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 2);
		List<String> cmd = mock.lastCommand();
		assertEquals("FT.SEARCH", cmd.get(0));
		assertEquals(INDEX, cmd.get(1));
		assertTrue(cmd.get(2).contains("=>[KNN 2 @vector $blob]"));
		assertTrue(cmd.contains("DIALECT"));
		assertEquals("2", cmd.get(cmd.size() - 1));
		assertEquals(2, results.size());
		// v2 距离 0.1 -> 余弦 0.9；v1 距离 0.2 -> 余弦 0.8；按相似度降序 v2 在前
		assertEquals("v2", results.get(0).id());
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("world", results.get(0).text());
		assertEquals("v1", results.get(1).id());
		assertEquals(0.8, results.get(1).score(), 1e-9);
		assertEquals("a.pdf", results.get(1).metadata().get("source"));
	}

	/** 带 filter：FT.SEARCH 查询串含预过滤括号。 */
	@Test
	public void testSearchWithFilter() {
		mock.enqueue("*1\r\n:0\r\n");
		baseBuilder().tagFields("tenant").build().similaritySearch(new float[] { 0.1f }, 1,
				FilterExpression.eq("tenant", "acme"));
		List<String> cmd = mock.lastCommand();
		assertTrue(cmd.get(2).contains("(@tenant:{acme})=>[KNN"));
	}

	/** DEL：删除命令正确，返回 true。 */
	@Test
	public void testDelete() {
		mock.enqueue(":1\r\n");
		assertTrue(baseBuilder().build().delete("v1"));
		List<String> cmd = mock.lastCommand();
		assertEquals("DEL", cmd.get(0));
		assertEquals("doc:v1", cmd.get(1));
	}

	/** FT.DROPINDEX：clear 命令。 */
	@Test
	public void testClear() {
		mock.enqueue("+OK\r\n");
		baseBuilder().build().clear();
		List<String> cmd = mock.lastCommand();
		assertEquals("FT.DROPINDEX", cmd.get(0));
		assertEquals(INDEX, cmd.get(1));
	}

	/** FT.INFO：解析 num_docs。 */
	@Test
	public void testSize() {
		String reply = "*4\r\n$10\r\nindex_name\r\n$3\r\nidx\r\n$8\r\nnum_docs\r\n$1\r\n7\r\n";
		mock.enqueue(reply);
		assertEquals(7, baseBuilder().build().size());
	}

	/** createIndex：FT.CREATE 含 VECTOR/FLOAT32/DIM/DISTANCE_METRIC COSINE。 */
	@Test
	public void testCreateIndex() {
		mock.enqueue("+OK\r\n");
		baseBuilder().tagFields("tenant").numericFields("score").build().createIndex();
		List<String> cmd = mock.lastCommand();
		assertEquals("FT.CREATE", cmd.get(0));
		int vecIdx = cmd.indexOf("VECTOR");
		assertEquals("VECTOR", cmd.get(vecIdx));
		assertEquals("FLOAT32", cmd.get(vecIdx + 4));
		assertEquals("3", cmd.get(vecIdx + 6));
		assertEquals("COSINE", cmd.get(vecIdx + 8));
		assertTrue(cmd.contains("TAG"));
		assertTrue(cmd.contains("NUMERIC"));
	}

	/** AUTH：设置密码后先发 AUTH 命令。 */
	@Test
	public void testAuth() {
		mock.enqueue("+OK\r\n"); // AUTH 响应
		mock.enqueue("+OK\r\n"); // HSET 响应
		baseBuilder().password("pw").build().add(new Vector("a", new float[] { 1f, 2f, 3f }, "t", Map.of()));
		assertEquals("AUTH", mock.commandHistory().get(0).get(0));
		assertEquals("pw", mock.commandHistory().get(0).get(1));
		assertEquals("HSET", mock.commandHistory().get(1).get(0));
	}

	/** 错误响应包装为 AiException。 */
	@Test
	public void testErrorReplyThrows() {
		mock.enqueue("-ERR unknown command\r\n");
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 连接失败包装为 AiException。 */
	@Test
	public void testConnectionRefusedThrows() {
		mock.close();
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** RESP 数组解析空结果。 */
	@Test
	public void testSearchEmpty() {
		mock.enqueue("*1\r\n:0\r\n");
		List<SimilaritySearchResult> results = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f }, 1);
		assertTrue(results.isEmpty());
	}

	/** clear 后 autoCreate 触发 FT.CREATE（构造时已发一次 CREATE，clear 再 DROP+CREATE）。 */
	@Test
	public void testClearAutoRecreate() {
		mock.enqueue("+OK\r\n"); // 构造期 CREATE
		mock.enqueue("+OK\r\n"); // DROPINDEX
		mock.enqueue("+OK\r\n"); // clear 后重建 CREATE
		baseBuilder().autoCreateIndex(true).build().clear();
		assertEquals("FT.CREATE", mock.commandHistory().get(0).get(0));
		assertEquals("FT.DROPINDEX", mock.commandHistory().get(1).get(0));
		assertEquals("FT.CREATE", mock.commandHistory().get(2).get(0));
	}

	/** 删除不存在的 key 返回 false。 */
	@Test
	public void testDeleteMissing() {
		mock.enqueue(":0\r\n");
		assertFalse(baseBuilder().build().delete("nope"));
	}

	/**
	 * 最小 RESP mock ServerSocket：accept 后循环读命令、按队列回写脚本化响应。
	 */
	private static final class MockRedis {

		private final ServerSocket server;
		private final Queue<byte[]> replies = new LinkedList<>();
		private final List<List<String>> history = new ArrayList<>();
		private final AtomicReference<List<String>> last = new AtomicReference<>();

		MockRedis() throws IOException {
			this.server = new ServerSocket(0);
			Thread t = new Thread(this::serveLoop, "mock-redis");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		void enqueue(String reply) {
			synchronized (replies) {
				replies.add(reply.getBytes(StandardCharsets.UTF_8));
			}
		}

		List<String> lastCommand() {
			return last.get();
		}

		List<List<String>> commandHistory() {
			synchronized (history) {
				return new ArrayList<>(history);
			}
		}

		private void serveLoop() {
			try {
				while (!server.isClosed()) {
					Socket s = server.accept();
					handleConnection(s);
				}
			} catch (IOException e) {
				// 服务关闭，结束。
			}
		}

		private void handleConnection(Socket s) throws IOException {
			s.setSoTimeout(2000);
			InputStream in = s.getInputStream();
			try {
				while (!s.isClosed()) {
					List<String> cmd;
					try {
						cmd = readCommand(in);
					} catch (IOException eof) {
						break;
					}
					if (cmd == null) {
						break;
					}
					last.set(cmd);
					synchronized (history) {
						history.add(cmd);
					}
					byte[] reply;
					synchronized (replies) {
						reply = replies.poll();
					}
					if (reply == null) {
						reply = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
					}
					s.getOutputStream().write(reply);
					s.getOutputStream().flush();
				}
			} finally {
				s.close();
			}
		}

		private static List<String> readCommand(InputStream in) throws IOException {
			int b = in.read();
			if (b == -1) {
				return null;
			}
			if (b != '*') {
				throw new IOException("expected *, got " + (char) b);
			}
			int count = (int) readLongLine(in);
			List<String> args = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				int d = in.read();
				if (d != '$') {
					throw new IOException("expected $, got " + (char) d);
				}
				int len = (int) readLongLine(in);
				byte[] buf = new byte[len];
				int read = 0;
				while (read < len) {
					int r = in.read(buf, read, len - read);
					if (r == -1) {
						throw new IOException("eof");
					}
					read += r;
				}
				args.add(new String(buf, StandardCharsets.UTF_8));
				in.read();
				in.read();
			}
			return args;
		}

		private static long readLongLine(InputStream in) throws IOException {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			int prev = -1;
			int c;
			while ((c = in.read()) != -1) {
				if (prev == '\r' && c == '\n') {
					byte[] bytes = out.toByteArray();
					return Long.parseLong(new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8));
				}
				out.write(c);
				prev = c;
			}
			throw new IOException("eof in line");
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
