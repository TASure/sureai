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
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiRateLimitException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.http.SseLineReader;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * HTTP 客户端抽象基类：封装 JSON POST、SSE 流式 POST、重试与错误映射。
 *
 * <p>子类需实现 {@link #applyAuth} 添加鉴权头，并可覆盖 {@link #mapError} 定制错误映射。</p>
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
		int attempt = 0;
		while (true) {
			HttpRequest request = newRequest(url, payload).build();
			HttpResponse<String> resp;
			try {
				resp = this.httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (IOException ex) {
				throw new AiTimeoutException("request failed: " + ex.getMessage(), ex);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AiException("request interrupted", ex);
			}
			int status = resp.statusCode();
			if (status >= 200 && status < 300) {
				return new PostResult(parseJson(resp.body()), resp.body());
			}
			String retryAfter = resp.headers().firstValue("Retry-After").orElse(null);
			if (isRetriable(status) && attempt < this.config.maxRetries()) {
				this.log.fine("retry " + (attempt + 1) + " after status " + status);
				sleepBackoff(attempt, retryAfter);
				attempt++;
				continue;
			}
			throw mapError(status, resp.body(), parseRetryAfter(retryAfter));
		}
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
			java.util.function.Consumer<JsonElement> chunkConsumer) {
		String url = resolveUrl(path);
		String payload = Json.stringify(body);
		int attempt = 0;
		while (true) {
			HttpRequest request = newRequest(url, payload).build();
			HttpResponse<java.io.InputStream> resp;
			try {
				resp = this.httpClient.send(request, BodyHandlers.ofInputStream());
			} catch (IOException ex) {
				throw new AiTimeoutException("stream request failed: " + ex.getMessage(), ex);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AiException("stream request interrupted", ex);
			}
			int status = resp.statusCode();
			if (status >= 200 && status < 300) {
				SseLineReader.read(resp.body(), StandardCharsets.UTF_8, ev -> {
					if ("[DONE]".equals(ev.data())) {
						return;
					}
					JsonElement el = Json.parse(ev.data());
					chunkConsumer.accept(el);
				});
				return;
			}
			String retryAfter = resp.headers().firstValue("Retry-After").orElse(null);
			String rawBody = readAll(resp);
			if (isRetriable(status) && attempt < this.config.maxRetries()) {
				sleepBackoff(attempt, retryAfter);
				attempt++;
				continue;
			}
			throw mapError(status, rawBody, parseRetryAfter(retryAfter));
		}
	}

	/** 读取错误响应体。 */
	private static String readAll(HttpResponse<java.io.InputStream> resp) {
		try (java.io.InputStream in = resp.body()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
		for (java.util.Map.Entry<String, String> e : this.config.extraHeaders().entrySet()) {
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

	/** 退避等待：Retry-After 优先，否则 1s/2s 指数。 */
	private static void sleepBackoff(int attempt, String retryAfter) {
		long ms;
		Integer sec = parseRetryAfter(retryAfter);
		if (sec != null) {
			ms = sec.longValue() * 1000L;
		} else {
			ms = 1000L * (long) Math.pow(2, attempt);
		}
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("retry interrupted", ex);
		}
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
