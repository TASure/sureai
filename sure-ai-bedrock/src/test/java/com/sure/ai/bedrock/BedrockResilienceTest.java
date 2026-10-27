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

package com.sure.ai.bedrock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.resilience.CircuitBreaker;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * {@link BedrockClient} 继承 {@link com.sure.ai.client.AbstractAiClient} 后的能力回归测试：
 * 验证 Bedrock 经基类获得重试/熔断/指标能力。
 *
 * <p>本地 {@link HttpServer} mock，零真实网络。使用包级构造器注入带 circuitBreaker /
 * metricsCollector / maxRetries 的 {@link AiConfig}。</p>
 *
 * @author sureai
 * @since 1.3.0
 */
public class BedrockResilienceTest {

	/** 合法 Bedrock Converse 成功响应。 */
	private static final String OK = "{\"output\":{\"message\":{\"role\":\"assistant\","
		+ "\"content\":[{\"text\":\"ok\"}]}},\"stopReason\":\"end_turn\","
		+ "\"usage\":{\"inputTokens\":5,\"outputTokens\":2,\"totalTokens\":7}}";

	private HttpServer server;
	private String endpoint;
	/** 已收到的 HTTP 请求数。 */
	private final AtomicInteger requestCount = new AtomicInteger();
	/** 前 N 次返回 500（带 Retry-After: 0 退避为 0ms），之后返回 200。 */
	private int failFirstN;

	/** 启动本地 mock。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.endpoint = "http://127.0.0.1:" + port;
		this.requestCount.set(0);
		this.failFirstN = 0;
		this.server.createContext("/", (HttpExchange exchange) -> {
			int n = this.requestCount.incrementAndGet();
			byte[] body;
			int status;
			if (n <= this.failFirstN) {
				// 可重试错误 + Retry-After: 0 → 退避 0ms，避免测试等待
				status = 500;
				body = "{\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Retry-After", "0");
			} else {
				status = 200;
				body = OK.getBytes(StandardCharsets.UTF_8);
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 用给定 AiConfig 构建指向 mock 的 BedrockClient。 */
	private BedrockClient newClient(AiConfig config) {
		return new BedrockClient(config, "secret", null, "us-east-1",
			BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET);
	}

	/** 指向 mock 的 AiConfig Builder。 */
	private AiConfig.Builder baseConfig() {
		return AiConfig.builder().apiKey("AKID").baseUrl(this.endpoint);
	}

	/** 发一次 chat。 */
	private static ChatRequest chatReq() {
		return ChatRequest.builder().model(BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET)
			.messages(List.of(ChatMessage.user("hi"))).build();
	}

	/**
	 * 熔断：maxRetries=0 时每次 chat 恰好 1 次 HTTP；3 次 500 触发 OPEN，第 4 次快速失败（0 新增网络）。
	 */
	@Test
	public void testCircuitBreakerOpensAndFastFails() {
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(3).windowSize(5).openTimeoutMs(60_000L).build();
		BedrockClient client = newClient(baseConfig().maxRetries(0).circuitBreaker(cb).build());
		this.failFirstN = Integer.MAX_VALUE; // 始终 500
		for (int i = 0; i < 3; i++) {
			assertThrows(AiApiException.class, () -> client.chat(chatReq()));
		}
		assertEquals(3, this.requestCount.get());
		assertEquals(CircuitBreaker.State.OPEN, cb.state());
		// 第 4 次：OPEN 快速失败，不发网络
		AiException e = assertThrows(AiException.class, () -> client.chat(chatReq()));
		assertTrue(e.getMessage().contains("Circuit breaker is OPEN"));
		assertEquals(3, this.requestCount.get());
		client.close();
	}

	/**
	 * 指标：mock 返回 200，断言 onRequestStart / onRequestSuccess / onTokenUsage 被回调。
	 */
	@Test
	public void testMetricsCollected() {
		RecordingMetrics metrics = new RecordingMetrics();
		BedrockClient client = newClient(baseConfig().maxRetries(0).metricsCollector(metrics).build());
		this.failFirstN = 0;
		ChatResponse resp = client.chat(chatReq());
		assertEquals("ok", resp.firstText());

		assertEquals(1, metrics.requestStart.get());
		assertEquals(1, metrics.requestSuccess.get());
		assertEquals(0, metrics.requestFailure.get());
		assertEquals(0, metrics.retried.get());
		// 非流式 chat 解析出用量后回调 onTokenUsage
		assertEquals(1, metrics.tokenUsage.get());
		assertEquals(5, metrics.lastPromptTokens.get());
		assertEquals(2, metrics.lastCompletionTokens.get());
		client.close();
	}

	/**
	 * 重试：maxRetries=2，前 2 次 500（Retry-After:0），第 3 次 200 → 最终成功且共发 3 次请求。
	 */
	@Test
	public void testRetryEventuallySucceeds() {
		RecordingMetrics metrics = new RecordingMetrics();
		BedrockClient client = newClient(baseConfig().maxRetries(2).metricsCollector(metrics).build());
		this.failFirstN = 2; // 前 2 次 500
		ChatResponse resp = client.chat(chatReq());
		assertEquals("ok", resp.firstText());
		assertEquals(3, this.requestCount.get());
		// 重试 2 次（第 1、2 次 500 触发 onRetry）
		assertEquals(2, metrics.retried.get());
		assertEquals(1, metrics.requestSuccess.get());
		assertEquals(0, metrics.requestFailure.get());
		client.close();
	}

	/** 记录型 MetricsCollector：用 AtomicInteger 统计回调次数。 */
	private static final class RecordingMetrics implements MetricsCollector {
		final AtomicInteger requestStart = new AtomicInteger();
		final AtomicInteger requestSuccess = new AtomicInteger();
		final AtomicInteger requestFailure = new AtomicInteger();
		final AtomicInteger retried = new AtomicInteger();
		final AtomicInteger tokenUsage = new AtomicInteger();
		final AtomicInteger lastPromptTokens = new AtomicInteger();
		final AtomicInteger lastCompletionTokens = new AtomicInteger();

		@Override
		public void onRequestStart(String path) {
			this.requestStart.incrementAndGet();
		}

		@Override
		public void onRequestSuccess(String path, int httpStatus, long durationMs) {
			this.requestSuccess.incrementAndGet();
		}

		@Override
		public void onRequestFailure(String path, int httpStatus, Exception exception, long durationMs) {
			this.requestFailure.incrementAndGet();
		}

		@Override
		public void onRetry(String path, int attempt, int httpStatus) {
			this.retried.incrementAndGet();
		}

		@Override
		public void onTokenUsage(String model, long promptTokens, long completionTokens,
				long totalTokens) {
			this.tokenUsage.incrementAndGet();
			this.lastPromptTokens.set((int) promptTokens);
			this.lastCompletionTokens.set((int) completionTokens);
		}
	}
}
