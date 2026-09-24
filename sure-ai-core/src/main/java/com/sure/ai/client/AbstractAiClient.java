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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;
import com.sure.ai.client.resilience.CircuitBreaker;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiRateLimitException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.http.SseLineReader;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.tool.thread.RateLimiter;

/**
 * HTTP 客户端抽象基类：封装 JSON POST、SSE 流式 POST、重试与错误映射。
 *
 * <p>子类需实现 {@link #applyAuth} 添加鉴权头，并可覆盖 {@link #mapError} 定制错误映射。</p>
 *
 * <p>可观测性（迭代二）：所有 HTTP 发送路径统一走 {@link #executeWithRetry} 模板，
 * 内置重试事件回调（{@link RetryListener}）、指标埋点（{@link MetricsCollector}）与
 * 客户端限流（{@link RateLimiter}）。未挂载时行为与重构前完全一致。</p>
 *
 * <p>熔断（{@link CircuitBreaker}）：在重试循环之外再包一层——重试是单次请求内部的
 * 指数退避，熔断是跨请求的故障状态机。未配置（null）时零开销，行为与之前完全一致；
 * OPEN 时不发起网络、不触发重试/指标/限流，直接快速失败。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public abstract class AbstractAiClient {

	/** 日志器 */
	protected final Logger log = Logger.getLogger(getClass().getName());

	/** 配置 */
	protected final AiConfig config;

	/** HTTP 客户端 */
	protected final HttpClient httpClient;

	/** 客户端限流器，rateLimitQps &lt;= 0 时为 null（关闭，零开销） */
	private final RateLimiter rateLimiter;

	/** 熔断器，未配置时为 null（关闭，零开销） */
	private final CircuitBreaker circuitBreaker;

	/**
	 * 构造并根据配置构建 HttpClient。
	 *
	 * @param config 配置
	 */
	protected AbstractAiClient(AiConfig config) {
		this.config = config;
		HttpClient.Builder cb = HttpClient.newBuilder()
			.connectTimeout(config.connectTimeout())
			.version(HttpClient.Version.HTTP_1_1);
		if (config.proxy() != null && !config.proxy().isBlank()) {
			cb.proxy(ProxySelector.of(parseProxy(config.proxy())));
		}
		this.httpClient = cb.build();
		this.rateLimiter = config.rateLimitQps() > 0 ? new RateLimiter(config.rateLimitQps()) : null;
		this.circuitBreaker = config.circuitBreaker();
	}

	/** 解析 host:port 为 SocketAddress。 */
	private static InetSocketAddress parseProxy(String proxy) {
		int idx = proxy.lastIndexOf(':');
		String host = proxy.substring(0, idx);
		int port = Integer.parseInt(proxy.substring(idx + 1));
		return new InetSocketAddress(host, port);
	}

	/**
	 * 子类添加鉴权头。
	 *
	 * @param requestBuilder 请求构建器
	 * @param config        配置
	 */
	protected abstract void applyAuth(HttpRequest.Builder requestBuilder, AiConfig config);

	/**
	 * JSON POST 并返回解析结果与原始报文。
	 *
	 * @param path 接口路径
	 * @param body 请求体
	 * @return 解析结果（含原始报文）
	 */
	protected PostResult doPostRaw(String path, JsonObject body) {
		String url = resolveUrl(path);
		String payload = Json.stringify(body);
		return executeWithRetry(path,
			() -> newRequest(url, payload).build(),
			BodyHandlers.ofString(StandardCharsets.UTF_8),
			b -> new PostResult(parseJson(b), b),
			(status, b) -> b);
	}

	/**
	 * JSON POST 并返回响应对象。
	 *
	 * <p>2xx 返回解析后的 JsonObject；非 2xx 经 {@link #mapError} 抛出；429/5xx 按 Retry-After
	 * 或指数退避重试 maxRetries 次。</p>
	 *
	 * @param path 接口路径
	 * @param body 请求体
	 * @return 响应 JSON
	 */
	protected JsonObject doPost(String path, JsonObject body) {
		return doPostRaw(path, body).json();
	}

	/**
	 * SSE 流式 POST。
	 *
	 * @param path          接口路径
	 * @param body          请求体
	 * @param chunkConsumer 每个 data 行解析出的 JSON 元素消费者
	 */
	protected void doPostStream(String path, JsonObject body,
			Consumer<JsonElement> chunkConsumer) {
		String url = resolveUrl(path);
		String payload = Json.stringify(body);
		executeWithRetry(path,
			() -> newRequest(url, payload).build(),
			BodyHandlers.ofInputStream(),
			(InputStream in) -> {
				SseLineReader.read(in, StandardCharsets.UTF_8, ev -> {
					if ("[DONE]".equals(ev.data())) {
						return;
					}
					chunkConsumer.accept(Json.parse(ev.data()));
				});
				return null;
			},
			(status, in) -> readAll(in));
	}

	/**
	 * JSON GET 并返回响应对象（用于异步任务轮询等场景）。
	 *
	 * <p>2xx 返回解析后的 JsonObject；非 2xx 经 {@link #mapError} 抛出；429/5xx 按 Retry-After
	 * 或指数退避重试 maxRetries 次。</p>
	 *
	 * @param path 接口路径
	 * @return 响应 JSON
	 */
	protected JsonObject doGet(String path) {
		return doGetRaw(path).json();
	}

	/**
	 * JSON GET 并返回解析结果与原始报文。
	 *
	 * @param path 接口路径
	 * @return 解析结果（含原始报文）
	 */
	protected PostResult doGetRaw(String path) {
		String url = resolveUrl(path);
		return executeWithRetry(path,
			() -> buildGetRequest(url).build(),
			BodyHandlers.ofString(StandardCharsets.UTF_8),
			b -> new PostResult(parseJson(b), b),
			(status, b) -> b);
	}

	/**
	 * JSON POST 并返回二进制响应体（用于 TTS 等返回音频二进制的接口）。
	 *
	 * @param path 接口路径
	 * @param body 请求体 JSON
	 * @return 二进制响应体
	 */
	protected byte[] doPostBinary(String path, JsonObject body) {
		String url = resolveUrl(path);
		String payload = Json.stringify(body);
		return executeWithRetry(path,
			() -> newRequest(url, payload).build(),
			BodyHandlers.ofByteArray(),
			b -> b,
			(status, b) -> new String(b, StandardCharsets.UTF_8));
	}

	/**
	 * multipart/form-data POST（用于 STT 音频文件上传等接口），返回解析结果与原始报文。
	 *
	 * @param path            接口路径
	 * @param textFields      文本字段（name → value）
	 * @param fileField       文件字段名
	 * @param fileName        文件名
	 * @param fileContentType 文件 MIME 类型
	 * @param fileData        文件二进制数据
	 * @return 解析结果（含原始报文）
	 */
	protected PostResult doPostMultipart(String path, Map<String, String> textFields,
			String fileField, String fileName, String fileContentType, byte[] fileData) {
		String url = resolveUrl(path);
		String boundary = "----sureai" + UUID.randomUUID().toString().replace("-", "");
		byte[] body = buildMultipartBody(boundary, textFields, fileField, fileName, fileContentType,
			fileData);
		return executeWithRetry(path,
			() -> buildMultipartRequest(url, boundary, body).build(),
			BodyHandlers.ofString(StandardCharsets.UTF_8),
			b -> new PostResult(parseJson(b), b),
			(status, b) -> b);
	}

	/**
	 * 统一执行模板（熔断外层）：熔断放行 → 重试内层。
	 *
	 * <p>熔断在重试循环之外再包一层：重试是单次请求内部的退避，熔断是跨请求的状态机。
	 * 未配置熔断器（null）时直接走内层，零开销；配置后，OPEN 时 {@link CircuitBreaker#allowRequest()}
	 * 返回 false，直接抛 {@link AiException} 快速失败——不发起网络、不触发重试/指标/限流。
	 * 请求成功回调 {@link CircuitBreaker#onSuccess()}，任何异常回调 {@link CircuitBreaker#onFailure()}
	 * 后原样抛出。</p>
	 *
	 * @param path            请求路径（回调与指标用）
	 * @param requestSupplier 每次 attempt 构建一个新请求（含重试）
	 * @param handler         响应体处理器
	 * @param successMapper   2xx 时把响应体映射为最终结果
	 * @param errorBodyReader 非 2xx 时把响应体读为错误字符串
	 * @param <T>             响应体类型
	 * @param <R>             最终结果类型
	 * @return successMapper 的结果
	 */
	private <T, R> R executeWithRetry(String path, Supplier<HttpRequest> requestSupplier,
			HttpResponse.BodyHandler<T> handler, Function<T, R> successMapper,
			BiFunction<Integer, T, String> errorBodyReader) {
		CircuitBreaker cb = this.circuitBreaker;
		if (cb == null) {
			return executeWithRetryInner(path, requestSupplier, handler, successMapper, errorBodyReader);
		}
		if (!cb.allowRequest()) {
			throw new AiException("Circuit breaker is OPEN for " + getClass().getSimpleName());
		}
		try {
			R result = executeWithRetryInner(path, requestSupplier, handler, successMapper, errorBodyReader);
			cb.onSuccess();
			return result;
		} catch (Exception ex) {
			cb.onFailure();
			throw ex;
		}
	}

	/**
	 * 统一重试执行内层：限流 → 指标开始 → 发送 → 2xx 成功 / 可重试退避 / 耗尽 mapError。
	 *
	 * <p>重试语义与重构前完全一致：429/500/502/503/504 可重试，Retry-After 优先、
	 * 否则 1s/2s/4s 指数退避，最多 maxRetries 次。IO/中断异常不重试，直接映射抛出。</p>
	 *
	 * @param path            请求路径（回调与指标用）
	 * @param requestSupplier 每次 attempt 构建一个新请求（含重试）
	 * @param handler         响应体处理器
	 * @param successMapper   2xx 时把响应体映射为最终结果
	 * @param errorBodyReader 非 2xx 时把响应体读为错误字符串
	 * @param <T>             响应体类型
	 * @param <R>             最终结果类型
	 * @return successMapper 的结果
	 */
	private <T, R> R executeWithRetryInner(String path, Supplier<HttpRequest> requestSupplier,
			HttpResponse.BodyHandler<T> handler, Function<T, R> successMapper,
			BiFunction<Integer, T, String> errorBodyReader) {
		MetricsCollector mc = this.config.metricsCollector();
		if (mc != null) {
			safeMetrics(() -> mc.onRequestStart(path));
		}
		long startNanos = System.nanoTime();
		int attempt = 0;
		while (true) {
			acquirePermit();
			HttpRequest request = requestSupplier.get();
			HttpResponse<T> resp;
			try {
				resp = this.httpClient.send(request, handler);
			} catch (IOException ex) {
				long dur = elapsedMs(startNanos);
				safeMetrics(() -> mcOnFailure(mc, path, -1, ex, dur));
				throw new AiTimeoutException("request failed: " + ex.getMessage(), ex);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				long dur = elapsedMs(startNanos);
				safeMetrics(() -> mcOnFailure(mc, path, -1, ex, dur));
				throw new AiException("request interrupted", ex);
			}
			int status = resp.statusCode();
			if (status >= 200 && status < 300) {
				long dur = elapsedMs(startNanos);
				R result = successMapper.apply(resp.body());
				if (mc != null) {
					final long d = dur;
					safeMetrics(() -> mc.onRequestSuccess(path, status, d));
				}
				return result;
			}
			String retryAfter = resp.headers().firstValue("Retry-After").orElse(null);
			if (isRetriable(status) && attempt < this.config.maxRetries()) {
				long backoffMs = computeBackoffMillis(attempt, retryAfter);
				fireOnRetry(attempt + 1, status, null, backoffMs, path);
				if (mc != null) {
					final int a = attempt + 1;
					safeMetrics(() -> mc.onRetry(path, a, status));
				}
				this.log.fine("retry " + (attempt + 1) + " after status " + status);
				sleepBackoff(backoffMs);
				attempt++;
				continue;
			}
			long dur = elapsedMs(startNanos);
			String rawBody = errorBodyReader.apply(status, resp.body());
			safeMetrics(() -> mcOnFailure(mc, path, status, null, dur));
			fireOnRetryExhausted(attempt, status, null, path);
			throw mapError(status, rawBody, parseRetryAfter(retryAfter));
		}
	}

	/** 指标 onRequestFailure 辅助（mc 可能为 null）。 */
	private static void mcOnFailure(MetricsCollector mc, String path, int status, Exception ex,
			long dur) {
		if (mc != null) {
			mc.onRequestFailure(path, status, ex, dur);
		}
	}

	/** 安全执行指标回调，异常仅记录 warning，不影响主流程。 */
	private static void safeMetrics(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException ex) {
			Logger.getLogger(AbstractAiClient.class.getName())
				.log(Level.WARNING, "metrics callback failed: " + ex.getMessage(), ex);
		}
	}

	/** 申请限流令牌（限流关闭时零开销）。 */
	private void acquirePermit() {
		if (this.rateLimiter == null) {
			return;
		}
		try {
			this.rateLimiter.acquire();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("rate limit acquire interrupted", ex);
		}
	}

	/** 回调所有注册的 RetryListener.onRetry（listener 异常不影响主流程）。 */
	private void fireOnRetry(int attempt, int status, Exception ex, long backoffMs, String path) {
		for (RetryListener l : this.config.retryListeners()) {
			try {
				l.onRetry(attempt, status, ex, backoffMs, path);
			} catch (RuntimeException e) {
				this.log.log(Level.WARNING, "retryListener.onRetry failed: " + e.getMessage(), e);
			}
		}
	}

	/** 回调所有注册的 RetryListener.onRetryExhausted（listener 异常不影响主流程）。 */
	private void fireOnRetryExhausted(int attempt, int status, Exception ex, String path) {
		for (RetryListener l : this.config.retryListeners()) {
			try {
				l.onRetryExhausted(attempt, status, ex, path);
			} catch (RuntimeException e) {
				this.log.log(Level.WARNING,
					"retryListener.onRetryExhausted failed: " + e.getMessage(), e);
			}
		}
	}

	/**
	 * 子类解析到 Token 用量时调用，转发给已挂载的 MetricsCollector（未挂载零开销）。
	 *
	 * @param model            模型名
	 * @param promptTokens     提示 token 数
	 * @param completionTokens 补全 token 数
	 * @param totalTokens      总 token 数
	 */
	protected void notifyTokenUsage(String model, long promptTokens, long completionTokens,
			long totalTokens) {
		MetricsCollector mc = this.config.metricsCollector();
		if (mc == null) {
			return;
		}
		safeMetrics(() -> mc.onTokenUsage(model, promptTokens, completionTokens, totalTokens));
	}

	/** 构造 multipart/form-data 请求体字节。 */
	private static byte[] buildMultipartBody(String boundary, Map<String, String> textFields,
			String fileField, String fileName, String fileContentType, byte[] fileData) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		String dashBoundary = "--" + boundary;
		String crlf = "\r\n";
		try {
			for (Map.Entry<String, String> e : textFields.entrySet()) {
				out.write((dashBoundary + crlf).getBytes(StandardCharsets.UTF_8));
				out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"" + crlf)
					.getBytes(StandardCharsets.UTF_8));
				out.write(crlf.getBytes(StandardCharsets.UTF_8));
				out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				out.write(crlf.getBytes(StandardCharsets.UTF_8));
			}
			if (fileData != null) {
				out.write((dashBoundary + crlf).getBytes(StandardCharsets.UTF_8));
				out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
					+ fileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
				out.write(("Content-Type: " + (fileContentType == null ? "application/octet-stream"
					: fileContentType) + crlf).getBytes(StandardCharsets.UTF_8));
				out.write(crlf.getBytes(StandardCharsets.UTF_8));
				out.write(fileData);
				out.write(crlf.getBytes(StandardCharsets.UTF_8));
			}
			out.write((dashBoundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiException("build multipart body failed: " + ex.getMessage(), ex);
		}
		return out.toByteArray();
	}

	/** 构造 multipart 请求构建器并应用鉴权与额外头。 */
	private HttpRequest.Builder buildMultipartRequest(String url, String boundary, byte[] body) {
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		applyAuth(rb, this.config);
		for (Map.Entry<String, String> e : this.config.extraHeaders().entrySet()) {
			rb.header(e.getKey(), e.getValue());
		}
		return rb;
	}

	/** 构造 GET 请求构建器并应用鉴权与额外头。 */
	private HttpRequest.Builder buildGetRequest(String url) {
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Accept", "application/json")
			.GET();
		applyAuth(rb, this.config);
		for (Map.Entry<String, String> e : this.config.extraHeaders().entrySet()) {
			rb.header(e.getKey(), e.getValue());
		}
		return rb;
	}

	/** 读取错误响应体。 */
	private static String readAll(InputStream in) {
		try (InputStream s = in) {
			return new String(s.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			return "";
		}
	}

	/** 构造请求构建器并应用鉴权与额外头。 */
	private HttpRequest.Builder newRequest(String url, String payload) {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
		applyAuth(b, this.config);
		for (Map.Entry<String, String> e : this.config.extraHeaders().entrySet()) {
			b.header(e.getKey(), e.getValue());
		}
		return b;
	}

	/** 拼接完整 URL。 */
	private String resolveUrl(String path) {
		String base = this.config.baseUrl();
		if (base == null || base.isBlank()) {
			throw new AiException("baseUrl is not configured");
		}
		if (base.endsWith("/") && path.startsWith("/")) {
			return base + path.substring(1);
		}
		if (!base.endsWith("/") && !path.startsWith("/")) {
			return base + "/" + path;
		}
		return base + path;
	}

	/** 是否可重试：429 或 5xx。 */
	private static boolean isRetriable(int status) {
		return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
	}

	/** 解析 Retry-After 秒数。 */
	private static Integer parseRetryAfter(String retryAfter) {
		if (retryAfter == null) {
			return null;
		}
		try {
			return (int) Double.parseDouble(retryAfter.trim());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	/** 计算退避毫秒数：Retry-After 优先，否则 1s/2s 指数。 */
	private static long computeBackoffMillis(int attempt, String retryAfter) {
		Integer sec = parseRetryAfter(retryAfter);
		if (sec != null) {
			return sec.longValue() * 1000L;
		}
		return 1000L * (long) Math.pow(2, attempt);
	}

	/** 退避等待指定毫秒。 */
	private static void sleepBackoff(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("retry interrupted", ex);
		}
	}

	/** 自 startNanos 起的耗时毫秒。 */
	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

	/**
	 * 将 HTTP 错误映射为异常（默认实现，子类可覆盖）。
	 *
	 * @param httpStatus HTTP 状态码
	 * @param rawBody    原始响应体
	 * @return 异常
	 */
	protected AiException mapError(int httpStatus, String rawBody) {
		return mapError(httpStatus, rawBody, null);
	}

	/** 内部带 retryAfter 的错误映射。 */
	private AiException mapError(int httpStatus, String rawBody, Integer retryAfterSeconds) {
		String message = "HTTP " + httpStatus;
		if (httpStatus == 401 || httpStatus == 403) {
			return new AiAuthException(httpStatus, message, rawBody);
		}
		if (httpStatus == 429) {
			return new AiRateLimitException(message, rawBody, retryAfterSeconds);
		}
		return new AiApiException(httpStatus, null, message, rawBody);
	}

	/**
	 * 序列化为 JSON 文本。
	 *
	 * @param obj 对象
	 * @return JSON 文本
	 */
	protected String toJson(Object obj) {
		return Json.stringify(obj);
	}

	/**
	 * 解析 JSON 文本为对象。
	 *
	 * @param json JSON 文本
	 * @return 对象
	 */
	protected JsonObject parseJson(String json) {
		return Json.parse(json).getAsJsonObject();
	}

	/**
	 * POST 结果：解析后的 JSON 与原始报文。
	 *
	 * @param json     解析后的对象
	 * @param rawBody  原始响应体
	 */
	protected record PostResult(JsonObject json, String rawBody) {
	}

	/**
	 * 释放资源（HttpClient 在 JDK21 可关闭，此处保持空实现以兼容接口约定）。
	 */
	public void close() {
		// 留空：JDK HttpClient 无需显式释放
	}
}
