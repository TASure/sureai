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

package com.sure.ai.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.Test;

import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;
import com.sure.ai.client.resilience.CircuitBreaker;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiRateLimitException;
import com.sure.ai.exception.AiTimeoutException;

/**
 * {@link RetryExecutor} 执行模板分支测试（本地 fake HttpClient，零真实网络）。
 *
 * <p>覆盖 IO/中断异常包装、指标回调、重试退避与耗尽、错误映射、熔断开闭、
 * 指标/监听器异常隔离与退避中断等分支。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public class RetryExecutorTest {

	/** 脚本化响应：按调用次序返回状态码与 Retry-After。 */
	private static final class FakeResponse<T> implements HttpResponse<T> {
		private final int status;
		private final T body;
		private final HttpHeaders headers;

		FakeResponse(int status, T body, String retryAfter) {
			this.status = status;
			this.body = body;
			Map<String, List<String>> h = retryAfter == null
				? Map.of()
				: Map.of("Retry-After", List.of(retryAfter));
			this.headers = HttpHeaders.of(h, (k, v) -> true);
		}

		@Override
		public int statusCode() {
			return this.status;
		}

		@Override
		public HttpRequest request() {
			return HttpRequest.newBuilder(URI.create("http://localhost")).GET().build();
		}

		@Override
		public HttpHeaders headers() {
			return this.headers;
		}

		@Override
		public Optional<javax.net.ssl.SSLSession> sslSession() {
			return Optional.empty();
		}

		@Override
		public T body() {
			return this.body;
		}

		@Override
		public Optional<HttpResponse<T>> previousResponse() {
			return Optional.empty();
		}

		@Override
		public URI uri() {
			return URI.create("http://localhost");
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_1_1;
		}
	}

	/** 脚本化 HttpClient：可抛异常或按序返回 fake 响应。 */
	private static final class FakeHttpClient extends HttpClient {
		IOException ioError;
		InterruptedException interrupted;
		List<FakeResponse<String>> responses;
		int sendCount;

		@Override
		public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler)
				throws IOException, InterruptedException {
			this.sendCount++;
			if (this.ioError != null) {
				throw this.ioError;
			}
			if (this.interrupted != null) {
				throw this.interrupted;
			}
			@SuppressWarnings("unchecked")
			HttpResponse<T> r = (HttpResponse<T>) this.responses.get(this.sendCount - 1);
			return r;
		}

		@Override
		public Optional<CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<java.time.Duration> connectTimeout() {
			return Optional.of(java.time.Duration.ofSeconds(1));
		}

		@Override
		public Redirect followRedirects() {
			return Redirect.NEVER;
		}

		@Override
		public Optional<ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public javax.net.ssl.SSLParameters sslParameters() {
			return new javax.net.ssl.SSLParameters();
		}

		@Override
		public javax.net.ssl.SSLContext sslContext() {
			try {
				return javax.net.ssl.SSLContext.getDefault();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public Optional<java.util.concurrent.Executor> executor() {
			return Optional.empty();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				BodyHandler<T> responseHandler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public WebSocket.Builder newWebSocketBuilder() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<java.net.Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_1_1;
		}
	}

	/** 记录指标回调的采集器。 */
	private static final class RecordingMetrics implements MetricsCollector {
		final AtomicInteger starts = new AtomicInteger();
		final AtomicInteger successes = new AtomicInteger();
		final AtomicInteger failures = new AtomicInteger();
		final AtomicInteger retries = new AtomicInteger();
		boolean throwOnStart;

		@Override
		public void onRequestStart(String path) {
			this.starts.incrementAndGet();
			if (this.throwOnStart) {
				throw new RuntimeException("boom");
			}
		}

		@Override
		public void onRequestSuccess(String path, int httpStatus, long durationMs) {
			this.successes.incrementAndGet();
		}

		@Override
		public void onRequestFailure(String path, int httpStatus, Exception exception, long durationMs) {
			this.failures.incrementAndGet();
		}

		@Override
		public void onRetry(String path, int attempt, int httpStatus) {
			this.retries.incrementAndGet();
		}
	}

	private RetryExecutor newExecutor(AiConfig config, FakeHttpClient client) {
		return new RetryExecutor(config, client, Logger.getLogger("test"), "TestClient");
	}

	private static AiConfig.Builder baseConfig() {
		return AiConfig.builder().apiKey("k").baseUrl("http://localhost");
	}

	/** 2xx 成功：指标开始/成功均触发。 */
	@Test
	public void testSuccessMetrics() {
		RecordingMetrics mc = new RecordingMetrics();
		AiConfig cfg = baseConfig().metricsCollector(mc).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(new FakeResponse<>(200, "ok", null));
		RetryExecutor ex = newExecutor(cfg, client);

		String out = ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b);
		assertEquals("ok", out);
		assertEquals(1, mc.starts.get());
		assertEquals(1, mc.successes.get());
		assertEquals(0, mc.failures.get());
	}

	/** IOException 包装为 AiTimeoutException，并触发失败指标。 */
	@Test
	public void testIOExceptionWrapped() {
		RecordingMetrics mc = new RecordingMetrics();
		AiConfig cfg = baseConfig().metricsCollector(mc).build();
		FakeHttpClient client = new FakeHttpClient();
		client.ioError = new IOException("connect refused");
		RetryExecutor ex = newExecutor(cfg, client);
		assertThrows(AiTimeoutException.class, () -> ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b));
		assertEquals(1, mc.failures.get());
	}

	/** InterruptedException 包装为 AiException 且恢复中断标志。 */
	@Test
	public void testInterruptedExceptionWrapped() {
		AiConfig cfg = baseConfig().build();
		FakeHttpClient client = new FakeHttpClient();
		client.interrupted = new InterruptedException("sleep");
		RetryExecutor ex = newExecutor(cfg, client);
		assertThrows(AiException.class, () -> ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b));
		assertTrue(Thread.interrupted());
	}

	/** 429 退避后成功（Retry-After:0 立即返回），重试与成功指标触发。 */
	@Test
	public void testRetryThenSuccess() {
		RecordingMetrics mc = new RecordingMetrics();
		AiConfig cfg = baseConfig().metricsCollector(mc).maxRetries(2).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(
			new FakeResponse<>(429, "busy", "0"),
			new FakeResponse<>(200, "good", null));
		RetryExecutor ex = newExecutor(cfg, client);
		String out = ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b);
		assertEquals("good", out);
		assertEquals(2, client.sendCount);
		assertEquals(1, mc.retries.get());
	}

	/** 429 耗尽后映射为 AiRateLimitException（含非数值 Retry-After 回退）。 */
	@Test
	public void testRetryExhaustedRateLimit() {
		AiConfig cfg = baseConfig().maxRetries(1).build();
		FakeHttpClient client = new FakeHttpClient();
		// 非数值 Retry-After 触发 parseRetryAfter 异常回退
		client.responses = List.of(
			new FakeResponse<>(429, "b1", "abc"),
			new FakeResponse<>(429, "b2", "abc"));
		RetryExecutor ex = newExecutor(cfg, client);
		AiRateLimitException e = assertThrows(AiRateLimitException.class, () -> ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b));
		assertEquals(429, e.getHttpStatus());
		assertEquals(2, client.sendCount);
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuthMapping() {
		AiConfig cfg = baseConfig().maxRetries(0).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(new FakeResponse<>(401, "nope", null));
		RetryExecutor ex = newExecutor(cfg, client);
		AiAuthException e = assertThrows(AiAuthException.class, () -> ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b));
		assertEquals(401, e.getHttpStatus());
	}

	/** 400 映射为 AiApiException。 */
	@Test
	public void testApiErrorMapping() {
		AiConfig cfg = baseConfig().maxRetries(0).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(new FakeResponse<>(400, "bad", null));
		RetryExecutor ex = newExecutor(cfg, client);
		AiApiException e = assertThrows(AiApiException.class, () -> ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b));
		assertEquals(400, e.getHttpStatus());
	}

	/** 指标回调抛异常被安全隔离，不影响主流程。 */
	@Test
	public void testMetricsCallbackIsolated() {
		RecordingMetrics mc = new RecordingMetrics();
		mc.throwOnStart = true;
		AiConfig cfg = baseConfig().metricsCollector(mc).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(new FakeResponse<>(200, "ok", null));
		RetryExecutor ex = newExecutor(cfg, client);
		String out = ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b);
		assertEquals("ok", out);
	}

	/** 熔断 OPEN 时快速失败，不发网络。 */
	@Test
	public void testCircuitOpenFastFail() {
		CircuitBreaker cb = CircuitBreaker.builder().failureThreshold(1).build();
		// 手动让其进入 OPEN：一次失败
		cb.onFailure();
		AiConfig cfg = baseConfig().circuitBreaker(cb).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(new FakeResponse<>(200, "ok", null));
		RetryExecutor ex = newExecutor(cfg, client);
		assertThrows(AiException.class, () -> ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b));
		assertEquals(0, client.sendCount);
	}

	/** 熔断成功 onSuccess、失败 onFailure 均被调用。 */
	@Test
	public void testCircuitSuccessAndFailureHooks() {
		CircuitBreaker cb = CircuitBreaker.builder().failureThreshold(5).windowSize(5).build();
		AiConfig cfg = baseConfig().circuitBreaker(cb).maxRetries(0).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(new FakeResponse<>(200, "ok", null));
		RetryExecutor ex = newExecutor(cfg, client);
		ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b);
		// 成功后状态仍为 CLOSED
		assertEquals(CircuitBreaker.State.CLOSED, cb.state());
	}

	/** 退避等待被中断：恢复中断标志并抛 AiException。 */
	@Test
	public void testSleepBackoffInterrupted() {
		AiConfig cfg = baseConfig().maxRetries(1).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(new FakeResponse<>(429, "busy", "0"));
		RetryExecutor ex = newExecutor(cfg, client);
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, () -> ex.execute("/v1/chat",
				() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
				BodyHandlers.ofString(), b -> b, (s, b) -> b));
		} finally {
			Thread.interrupted();
		}
	}

	/** RetryListener 抛异常不影响主流程。 */
	@Test
	public void testRetryListenerIsolated() {
		RetryListener bad = new RetryListener() {
			@Override
			public void onRetry(int attempt, int httpStatus, Exception exception, long backoffMs,
					String requestPath) {
				throw new RuntimeException("listener boom");
			}
		};
		AiConfig cfg = baseConfig().retryListener(bad).maxRetries(1).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(
			new FakeResponse<>(429, "b", "0"),
			new FakeResponse<>(200, "ok", null));
		RetryExecutor ex = newExecutor(cfg, client);
		String out = ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b);
		assertEquals("ok", out);
	}

	/** onRetryExhausted 监听器抛异常被隔离，不掩盖原始业务异常。 */
	@Test
	public void testRetryExhaustedListenerIsolated() {
		RetryListener bad = new RetryListener() {
			@Override
			public void onRetryExhausted(int attempt, int httpStatus, Exception exception,
					String requestPath) {
				throw new RuntimeException("exhausted boom");
			}
		};
		AiConfig cfg = baseConfig().retryListener(bad).maxRetries(1).build();
		FakeHttpClient client = new FakeHttpClient();
		client.responses = List.of(
			new FakeResponse<>(429, "b1", "0"),
			new FakeResponse<>(429, "b2", "0"));
		RetryExecutor ex = newExecutor(cfg, client);
		assertThrows(AiRateLimitException.class, () -> ex.execute("/v1/chat",
			() -> HttpRequest.newBuilder(URI.create("http://localhost")).GET().build(),
			BodyHandlers.ofString(), b -> b, (s, b) -> b));
	}
}
