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

package com.sure.ai.client.resilience;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.test.tag.Slow;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

/**
 * 熔断器集成测试：本地 HttpServer mock，零真实网络。
 *
 * <p>用 {@code maxRetries=0} 保证每次 chat 恰好 1 次 HTTP 请求，便于精确断言
 * 熔断器 OPEN 后不再发请求。含熔断时间窗口等待，标注为 {@link Slow}，
 * 在 {@code -Pfast} 构建中排除。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
@Category(Slow.class)
public class CircuitBreakerIntegrationTest {

	private static final String OK = "{\"id\":\"c\",\"model\":\"gpt\",\"choices\":[{\"index\":0,"
		+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

	private HttpServer server;
	private String baseUrl;
	private final AtomicInteger requestCount = new AtomicInteger();
	/** "fail"=返回 500；"ok"=返回 200。 */
	private final AtomicReference<String> mode = new AtomicReference<>("fail");

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		this.requestCount.set(0);
		this.mode.set("fail");
		this.server.createContext("/", (HttpExchange exchange) -> {
			this.requestCount.incrementAndGet();
			byte[] body;
			int status;
			if ("ok".equals(this.mode.get())) {
				status = 200;
				body = OK.getBytes(StandardCharsets.UTF_8);
			} else {
				status = 500;
				body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造带熔断器的客户端（maxRetries=0，阈值 3，短超时便于恢复测试）。 */
	private OpenAiCompatClient newClient(CircuitBreaker cb) {
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.maxRetries(0).circuitBreaker(cb).build();
		return new OpenAiCompatClient(cfg);
	}

	/** 发一次 chat。 */
	private static void chat(OpenAiCompatClient c) {
		c.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build());
	}

	/** 500 三次触发 OPEN，第四次快速失败（0 新增网络请求）。 */
	@Test
	public void testOpensAndFastFails() {
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(3).windowSize(5).openTimeoutMs(60_000L).build();
		OpenAiCompatClient client = newClient(cb);
		// 三次 500 → 三次 HTTP 请求，每次抛 AiApiException
		for (int i = 0; i < 3; i++) {
			assertThrows(AiApiException.class, () -> chat(client));
		}
		assertEquals(3, this.requestCount.get());
		assertEquals(CircuitBreaker.State.OPEN, cb.state());
		// 第四次：OPEN 快速失败，不发网络
		AiException e = assertThrows(AiException.class, () -> chat(client));
		assertTrue(e.getMessage().contains("Circuit breaker is OPEN"));
		assertEquals(3, this.requestCount.get());
		client.close();
	}

	/** OPEN 超时后探测成功 → 回到 CLOSED，后续请求正常。 */
	@Test
	public void testRecoversAfterTimeout() throws InterruptedException {
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(3).windowSize(5).openTimeoutMs(200L).build();
		OpenAiCompatClient client = newClient(cb);
		for (int i = 0; i < 3; i++) {
			assertThrows(AiApiException.class, () -> chat(client));
		}
		assertEquals(3, this.requestCount.get());
		assertEquals(CircuitBreaker.State.OPEN, cb.state());
		// 切到 200，轮询等待 OPEN 超时惰性转为 HALF_OPEN（替代固定 Thread.sleep，避免 CI 调度抖动）
		this.mode.set("ok");
		long deadline = System.currentTimeMillis() + 5_000L;
		while (cb.state() != CircuitBreaker.State.HALF_OPEN
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(10L);
		}
		assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
		// 探测请求成功 → CLOSED
		chat(client);
		assertEquals(4, this.requestCount.get());
		assertEquals(CircuitBreaker.State.CLOSED, cb.state());
		// 后续正常请求
		chat(client);
		assertEquals(5, this.requestCount.get());
		client.close();
	}

	/** 不配置熔断器时行为与之前一致（每次失败都真实发请求，不快速失败）。 */
	@Test
	public void testNoCircuitBreakerBackwardCompatible() {
		OpenAiCompatClient client = new OpenAiCompatClient(AiConfig.builder()
			.apiKey("k").baseUrl(this.baseUrl).maxRetries(0).build());
		// 连续多次 500：每次都真实打到服务端，不存在快速失败短路
		for (int i = 0; i < 3; i++) {
			assertThrows(AiApiException.class, () -> chat(client));
		}
		assertEquals(3, this.requestCount.get());
		client.close();
	}
}
