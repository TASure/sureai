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

package com.sure.ai.rag.store.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatResponse;

/**
 * {@link RedisCacheStore} 单元测试：本地 {@link ServerSocket} mock RESP，零真实网络。
 *
 * @author sureai
 * @since 2.4.0
 */
public class RedisCacheStoreTest {

	/** 一段 OpenAI 兼容响应体（含 tool_calls / reasoning_content / usage）。 */
	private static final String BODY = "{\"id\":\"cm-1\",\"model\":\"gpt-4o\","
		+ "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"你好\","
		+ "\"reasoning_content\":\"思考过程\","
		+ "\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\"get_weather\","
		+ "\"arguments\":\"{\\\"city\\\":\\\"西安\\\"}\"}}]},"
		+ "\"finish_reason\":\"tool_calls\"}],"
		+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";

	private MockRedis mock;

	@Before
	public void setUp() throws IOException {
		this.mock = new MockRedis();
	}

	@After
	public void tearDown() {
		this.mock.close();
	}

	private RedisCacheStore.Builder baseBuilder() {
		return RedisCacheStore.builder().host("127.0.0.1").port(this.mock.port());
	}

	private static ChatResponse sent(String rawJson) {
		// 只关心存储的 rawJson；choices 占位即可（存储路径不读 choices）。
		return ChatResponse.of("cm-1", "gpt-4o", List.of(), null, rawJson);
	}

	/** put->get 往返：SET 含 PX 与前缀，GET 返回的 bulk 被还原为完整 ChatResponse。 */
	@Test
	public void putGetRoundTrip() {
		this.mock.enqueue("+OK\r\n");                       // SET
		this.mock.enqueue("$" + BODY.getBytes(StandardCharsets.UTF_8).length + "\r\n" + BODY + "\r\n");
		RedisCacheStore store = baseBuilder().build();
		store.put("k1", sent(BODY), 60_000L);

		List<String> setCmd = this.mock.commandHistory().get(0);
		assertEquals("SET", setCmd.get(0));
		assertEquals("sureai:cache:k1", setCmd.get(1));
		assertEquals(BODY, setCmd.get(2));
		assertEquals("PX", setCmd.get(3));
		assertEquals("60000", setCmd.get(4));

		ChatResponse got = store.get("k1");
		assertNotNull(got);
		assertEquals("cm-1", got.id());
		assertEquals("gpt-4o", got.model());
		assertEquals("你好", got.firstText());
		assertEquals(10, got.usage().promptTokens());
		assertEquals(5, got.usage().completionTokens());
		assertEquals(15, got.usage().totalTokens());
		assertEquals("思考过程", got.choices().get(0).message().reasoningContent());
		assertEquals("get_weather", got.choices().get(0).message().toolCalls().get(0).name());
		assertEquals(BODY, got.rawJson());

		List<String> getCmd = this.mock.commandHistory().get(1);
		assertEquals("GET", getCmd.get(0));
		assertEquals("sureai:cache:k1", getCmd.get(1));
		store.close();
	}

	/** ttlMillis<=0 时使用 store 默认 TTL（默认 5 分钟）。 */
	@Test
	public void defaultTtlWhenNonPositive() {
		this.mock.enqueue("+OK\r\n");
		baseBuilder().build().put("k", sent(BODY), -1L);
		List<String> cmd = this.mock.commandHistory().get(0);
		assertEquals("PX", cmd.get(3));
		assertEquals("300000", cmd.get(4));
	}

	/** 自定义 key 前缀体现在命令里。 */
	@Test
	public void customPrefix() {
		this.mock.enqueue("+OK\r\n");
		baseBuilder().keyPrefix("acme:").build().put("k", sent(BODY), 1000L);
		assertEquals("acme:k", this.mock.commandHistory().get(0).get(1));
	}

	/** GET 返回 $-1（nil）→ get 返回 null（未命中/已过期）。 */
	@Test
	public void getMissReturnsNull() {
		this.mock.enqueue("$-1\r\n");
		assertNull(baseBuilder().build().get("nope"));
	}

	/** remove 发 DEL 且带前缀。 */
	@Test
	public void removeSendsDel() {
		this.mock.enqueue(":1\r\n");
		baseBuilder().build().remove("k1");
		List<String> cmd = this.mock.commandHistory().get(0);
		assertEquals("DEL", cmd.get(0));
		assertEquals("sureai:cache:k1", cmd.get(1));
	}

	/** clear 用 SCAN 迭代收集前缀键后批量 DEL（不使用阻塞式 KEYS）。 */
	@Test
	public void clearUsesScanAndDel() {
		// 第一次 SCAN：cursor=5，两个键
		this.mock.enqueue("*2\r\n$1\r\n5\r\n*2\r\n$14\r\nsureai:cache:a\r\n$14\r\nsureai:cache:b\r\n");
		// 第二次 SCAN：cursor=0，空数组
		this.mock.enqueue("*2\r\n$1\r\n0\r\n*0\r\n");
		this.mock.enqueue(":2\r\n"); // DEL 结果
		baseBuilder().build().clear();

		List<List<String>> hist = this.mock.commandHistory();
		assertEquals("SCAN", hist.get(0).get(0));
		assertEquals("0", hist.get(0).get(1));
		assertTrue(hist.get(0).contains("sureai:cache:*"));
		assertEquals("SCAN", hist.get(1).get(0));
		assertEquals("5", hist.get(1).get(1));
		assertEquals("DEL", hist.get(2).get(0));
		assertEquals("sureai:cache:a", hist.get(2).get(1));
		assertEquals("sureai:cache:b", hist.get(2).get(2));
	}

	/** 错误回复（-ERR）包装为 AiException。 */
	@Test
	public void errorReplyThrows() {
		this.mock.enqueue("-ERR wrongpass\r\n");
		assertThrows(AiException.class, () -> baseBuilder().build().put("k", sent(BODY), 1000L));
	}

	/** 连接被拒绝（mock 关闭）→ AiException（fail-fast）。 */
	@Test
	public void connectionRefusedThrows() {
		this.mock.close();
		assertThrows(AiException.class, () -> baseBuilder().build().get("k"));
	}

	/** 多条命令复用同一条 Socket 连接（只 accept 一次）。 */
	@Test
	public void reusesSingleConnection() {
		this.mock.enqueue("+OK\r\n");
		this.mock.enqueue("+OK\r\n");
		RedisCacheStore store = baseBuilder().build();
		store.put("a", sent(BODY), 1000L);
		store.put("b", sent(BODY), 1000L);
		assertEquals(1, this.mock.acceptCount());
		store.close();
	}

	/** 连接中途断开后自动重连：首条命令成功；mock 随后关连接，第二条命令经重连重试成功。 */
	@Test
	public void reconnectAfterDroppedConnection() {
		this.mock.dropAfterReply(); // 每条连接写完回复后立即关闭
		this.mock.enqueue("+OK\r\n");  // 第一次 put：连上、写 SET、读 OK、连接被 mock 关闭
		this.mock.enqueue("+OK\r\n");  // 第二次 put：旧连接已死 → 重连 → 新连接读 OK
		RedisCacheStore store = baseBuilder().build();
		store.put("a", sent(BODY), 1000L);
		store.put("b", sent(BODY), 1000L);
		assertEquals(2, this.mock.acceptCount()); // 重连发生，第二次 accept
		assertEquals("SET", this.mock.commandHistory().get(0).get(0));
		assertEquals("SET", this.mock.commandHistory().get(1).get(0));
		store.close();
	}

	/**
	 * 最小 RESP mock ServerSocket：accept 后循环读命令、按队列回写脚本化响应。
	 * 支持「写完回复即关闭连接」以模拟断线。
	 */
	private static final class MockRedis {

		private final ServerSocket server;
		private final Queue<byte[]> replies = new LinkedList<>();
		private final List<List<String>> history = new ArrayList<>();
		private final AtomicReference<List<String>> last = new AtomicReference<>();
		private final AtomicInteger accepts = new AtomicInteger();
		private volatile boolean dropAfterReply;

		MockRedis() throws IOException {
			this.server = new ServerSocket(0);
			Thread t = new Thread(this::serveLoop, "mock-redis-cache");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return this.server.getLocalPort();
		}

		int acceptCount() {
			return this.accepts.get();
		}

		void dropAfterReply() {
			this.dropAfterReply = true;
		}

		void enqueue(String reply) {
			synchronized (this.replies) {
				this.replies.add(reply.getBytes(StandardCharsets.UTF_8));
			}
		}

		List<List<String>> commandHistory() {
			synchronized (this.history) {
				return new ArrayList<>(this.history);
			}
		}

		private void serveLoop() {
			try {
				while (!this.server.isClosed()) {
					Socket s = this.server.accept();
					this.accepts.incrementAndGet();
					handleConnection(s);
				}
			} catch (IOException e) {
				// 服务关闭，结束。
			}
		}

		private void handleConnection(Socket s) {
			try {
				s.setSoTimeout(2000);
				InputStream in = s.getInputStream();
				try {
					while (!s.isClosed()) {
						List<String> cmd = readCommand(in);
						if (cmd == null) {
							break;
						}
						this.last.set(cmd);
						synchronized (this.history) {
							this.history.add(cmd);
						}
						byte[] reply;
						synchronized (this.replies) {
							reply = this.replies.poll();
						}
						if (reply == null) {
							reply = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
						}
						s.getOutputStream().write(reply);
						s.getOutputStream().flush();
						if (this.dropAfterReply) {
							break; // 模拟连接断开
						}
					}
				} catch (IOException e) {
					// 对端断开，结束本连接。
				} finally {
					s.close();
				}
			} catch (IOException e) {
				// 忽略
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
				this.server.close();
			} catch (IOException ignored) {
				// ignore
			}
		}
	}
}
