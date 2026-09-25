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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.BiFunction;
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
import com.sure.tool.thread.RateLimiter;

/**
 * HTTP 重试执行器（包内可见协作组件）。
 *
 * <p>从 {@link AbstractAiClient} 拆出：统一封装跨切面的执行模板——</p>
 *
 * <ul>
 *   <li><b>熔断外层</b>（{@link CircuitBreaker}）：OPEN 时不发网络、直接快速失败；
 *       成功 {@code onSuccess}、异常 {@code onFailure}。未配置（null）零开销。</li>
 *   <li><b>重试内层</b>：限流取令牌 → 指标开始 → 发送 → 2xx 成功 / 429·5xx 退避重试 /
 *       耗尽后 {@link #mapError 错误映射}。退避优先取 {@code Retry-After}，否则 1s/2s/4s 指数。</li>
 *   <li><b>限流</b>（{@link RateLimiter}）与<b>指标</b>（{@link MetricsCollector}）、
 *       <b>重试回调</b>（{@link RetryListener}）均在此交汇；未挂载时零开销。</li>
 * </ul>
 *
 * <p>行为与重构前逐行一致：仅承担执行与横切关注点，不参与请求构建或业务错误定制。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
final class RetryExecutor {

	private final AiConfig config;

	private final HttpClient httpClient;

	/** 客户端日志器（命名为具体子类，用于 retry 细粒度日志与 listener 告警）。 */
	private final Logger log;

	/** 具体客户端简单名（熔断 OPEN 文案用，保持与重构前 {@code getClass().getSimpleName()} 一致）。 */
	private final String clientName;

	private final RateLimiter rateLimiter;

	private final CircuitBreaker circuitBreaker;

	RetryExecutor(AiConfig config, HttpClient httpClient, Logger log, String clientName) {
		this.config = config;
		this.httpClient = httpClient;
		this.log = log;
		this.clientName = clientName;
		this.rateLimiter = config.rateLimitQps() > 0 ? new RateLimiter(config.rateLimitQps()) : null;
		this.circuitBreaker = config.circuitBreaker();
	}

	/**
	 * 统一执行模板（熔断外层 → 重试内层）。
	 *
	 * @param path            请求路径（回调与指标用）
	 * @param requestSupplier 每次 attempt 构建一个新请求（含重试）
	 * @param handler         响应体处理器
	 * @param successMapper  2xx 时把响应体映射为最终结果
	 * @param errorBodyReader 非 2xx 时把响应体读为错误字符串
	 * @param <T>             响应体类型
	 * @param <R>             最终结果类型
	 * @return successMapper 的结果
	 */
	<T, R> R execute(String path, Supplier<HttpRequest> requestSupplier,
			HttpResponse.BodyHandler<T> handler, Function<T, R> successMapper,
			BiFunction<Integer, T, String> errorBodyReader) {
		CircuitBreaker cb = this.circuitBreaker;
		if (cb == null) {
			return executeWithRetryInner(path, requestSupplier, handler, successMapper, errorBodyReader);
		}
		if (!cb.allowRequest()) {
			throw new AiException("Circuit breaker is OPEN for " + this.clientName);
		}
		try {
			R result = executeWithRetryInner(path, requestSupplier, handler, successMapper,
				errorBodyReader);
			cb.onSuccess();
			return result;
		} catch (Exception ex) {
			cb.onFailure();
			throw ex;
		}
	}

	/** 重试内层：限流 → 指标开始 → 发送 → 2xx 成功 / 可重试退避 / 耗尽 mapError。 */
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
	static void safeMetrics(Runnable r) {
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
	 * 将 HTTP 错误映射为异常（带 retryAfter 版本，重试内层直接调用）。
	 *
	 * <p>与重构前的 private 三参 {@code mapError} 行为一致；{@link AbstractAiClient} 的
	 * 两参 {@code mapError} 委托到这里，子类覆写两参版本不影响重试内层（与重构前一致）。</p>
	 */
	static AiException mapError(int httpStatus, String rawBody, Integer retryAfterSeconds) {
		String message = "HTTP " + httpStatus;
		if (httpStatus == 401 || httpStatus == 403) {
			return new AiAuthException(httpStatus, message, rawBody);
		}
		if (httpStatus == 429) {
			return new AiRateLimitException(message, rawBody, retryAfterSeconds);
		}
		return new AiApiException(httpStatus, null, message, rawBody);
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
}
