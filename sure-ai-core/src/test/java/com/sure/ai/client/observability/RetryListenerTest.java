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

package com.sure.ai.client.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * {@link RetryListener} 回调集成测试：本地 HttpServer mock，零真实网络。
 *
 * @author sureai
 * @since 0.3.0
 */
public class RetryListenerTest {

	private static final String OK = "{\"id\":\"c\",\"model\":\"gpt\",\"choices\":[{\"index\":0,"
		+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

	private HttpServer server;
	private String baseUrl;
	private final AtomicInteger requestCount = new AtomicInteger();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		this.requestCount.set(0);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 注册处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.requestCount.incrementAndGet();
			h.handle(exchange);
		});
	}

	/** 发送 JSON 响应。 */
	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 处理器函数式接口。 */
	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws IOException;
	}

	/** 构造客户端。 */
	private OpenAiCompatClient client(AiConfig cfg) {
		return new OpenAiCompatClient(cfg);
	}

	/** 发送一次 chat。 */
	private static ChatResponse chat(OpenAiCompatClient c) {
		return c.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build());
	}

	/** 录制事件的监听器。 */
	private static final class RecordingListener implements RetryListener {
		final AtomicInteger onRetryCount = new AtomicInteger();
		final AtomicInteger exhaustedCount = new AtomicInteger();
		final AtomicInteger lastAttempt = new AtomicInteger(-1);
		final AtomicInteger lastStatus = new AtomicInteger(-1);
		final AtomicLongRef backoff = new AtomicLongRef();
		final AtomicReference<String> path = new AtomicReference<>();

		@Override
		public void onRetry(int attempt, int httpStatus, Exception exception, long backoffMs,
				String requestPath) {
			this.onRetryCount.incrementAndGet();
			this.lastAttempt.set(attempt);
			this.lastStatus.set(httpStatus);
			this.backoff.value = backoffMs;
			this.path.set(requestPath);
		}

		@Override
		public void onRetryExhausted(int attempt, int httpStatus, Exception exception,
				String requestPath) {
			this.exhaustedCount.incrementAndGet();
			this.lastAttempt.set(attempt);
			this.lastStatus.set(httpStatus);
			this.path.set(requestPath);
		}
	}

	/** long 引用。 */
	private static final class AtomicLongRef {
		long value;
	}

	/** 429 后重试成功：onRetry 被回调且参数正确。 */
	@Test
	public void testRetryListenerOnRetryCalled() {
		handle(ex -> {
			if (this.requestCount.get() == 1) {
				ex.getResponseHeaders().set("Retry-After", "0");
				respond(ex, 429, "{\"error\":\"busy\"}");
			} else {
				respond(ex, 200, OK);
			}
		});
		RecordingListener listener = new RecordingListener();
		OpenAiCompatClient c = client(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.retryListener(listener).build());
		ChatResponse resp = chat(c);
		assertEquals("ok", resp.firstText());
		assertEquals(1, listener.onRetryCount.get());
		assertEquals(1, listener.lastAttempt.get());
		assertEquals(429, listener.lastStatus.get());
		assertTrue(listener.backoff.value >= 0);
		assertEquals("/chat/completions", listener.path.get());
		assertEquals(0, listener.exhaustedCount.get());
		c.close();
	}

	/** 持续 500、maxRetries=1：onRetryExhausted 被回调并抛异常。 */
	@Test
	public void testRetryListenerOnRetryExhausted() {
		handle(ex -> {
			ex.getResponseHeaders().set("Retry-After", "0");
			respond(ex, 500, "{\"error\":\"boom\"}");
		});
		RecordingListener listener = new RecordingListener();
		OpenAiCompatClient c = client(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.maxRetries(1).retryListener(listener).build());
		assertThrows(Exception.class, () -> chat(c));
		// maxRetries=1：发生 1 次重试（onRetry），随后耗尽（onRetryExhausted）
		assertEquals(1, listener.onRetryCount.get());
		assertEquals(1, listener.exhaustedCount.get());
		assertEquals(500, listener.lastStatus.get());
		assertEquals("/chat/completions", listener.path.get());
		assertEquals(2, this.requestCount.get());
		c.close();
	}

	/** listener 自身抛异常不影响主流程。 */
	@Test
	public void testListenerExceptionDoesNotBreak() {
		handle(ex -> {
			if (this.requestCount.get() == 1) {
				ex.getResponseHeaders().set("Retry-After", "0");
				respond(ex, 429, "{\"error\":\"busy\"}");
			} else {
				respond(ex, 200, OK);
			}
		});
		RetryListener throwing = new RetryListener() {
			@Override
			public void onRetry(int attempt, int httpStatus, Exception exception, long backoffMs,
					String requestPath) {
				throw new RuntimeException("listener boom");
			}
		};
		OpenAiCompatClient c = client(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.retryListener(throwing).build());
		ChatResponse resp = chat(c);
		assertEquals("ok", resp.firstText());
		c.close();
	}

	/** 不注册 listener：行为与重构前一致（429 重试后成功）。 */
	@Test
	public void testNoListenerDefaultBehavior() {
		handle(ex -> {
			if (this.requestCount.get() == 1) {
				ex.getResponseHeaders().set("Retry-After", "0");
				respond(ex, 429, "{\"error\":\"busy\"}");
			} else {
				respond(ex, 200, OK);
			}
		});
		OpenAiCompatClient c = client(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
		ChatResponse resp = chat(c);
		assertEquals("ok", resp.firstText());
		assertEquals(2, this.requestCount.get());
		c.close();
	}

	/** 多次 retryListener 累加 + retryListeners 批量。 */
	@Test
	public void testMultipleListenersAccumulate() {
		handle(ex -> {
			ex.getResponseHeaders().set("Retry-After", "0");
			respond(ex, 429, "{\"error\":\"busy\"}");
		});
		RecordingListener l1 = new RecordingListener();
		RecordingListener l2 = new RecordingListener();
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.maxRetries(1)
			.retryListener(l1)
			.retryListeners(List.of(l2))
			.build();
		assertEquals(2, cfg.retryListeners().size());
		OpenAiCompatClient c = client(cfg);
		assertThrows(Exception.class, () -> chat(c));
		assertEquals(1, l1.onRetryCount.get());
		assertEquals(1, l2.onRetryCount.get());
		assertEquals(1, l1.exhaustedCount.get());
		assertEquals(1, l2.exhaustedCount.get());
		c.close();
	}
}
