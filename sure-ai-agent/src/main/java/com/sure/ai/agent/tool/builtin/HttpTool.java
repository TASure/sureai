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

package com.sure.ai.agent.tool.builtin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;

/**
 * HTTP 工具：基于 JDK {@link HttpClient} 的 GET/POST 请求。
 *
 * <p>仅允许 http/https scheme；响应体超过 {@code maxResponseLength} 截断并追加
 * {@code "...[truncated]"}。任何异常都转为可读错误文本，不向上抛出。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class HttpTool implements ToolHandler {

	/** 工具名。 */
	public static final String TOOL_NAME = "http_request";

	private static final int DEFAULT_MAX_RESPONSE_LENGTH = 8000;
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient client;
	private final Map<String, String> defaultHeaders;
	private final int maxResponseLength;
	private final Duration timeout;

	private HttpTool(Builder b) {
		this.client = HttpClient.newBuilder()
			.connectTimeout(b.timeout)
			.build();
		Map<String, String> copy = new LinkedHashMap<>();
		if (b.defaultHeaders != null) {
			copy.putAll(b.defaultHeaders);
		}
		this.defaultHeaders = Map.copyOf(copy);
		this.maxResponseLength = b.maxResponseLength;
		this.timeout = b.timeout;
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 返回工具函数声明（供 ToolRegistry 注册）。
	 *
	 * @return ToolFunction
	 */
	public static ToolFunction toToolFunction() {
		return ToolFunction.of(TOOL_NAME,
			"Send an HTTP GET/POST request and return the response body (http/https only).",
			"""
			{
			  "type": "object",
			  "properties": {
			    "url": { "type": "string", "description": "target URL, http or https" },
			    "method": { "type": "string", "enum": ["GET", "POST"], "description": "HTTP method, default GET" },
			    "body": { "type": "string", "description": "request body for POST" },
			    "headers": { "type": "object", "description": "extra request headers" }
			  },
			  "required": ["url"]
			}
			""");
	}

	@Override
	public String execute(JsonObject args) {
		try {
			if (args == null || !args.has("url")) {
				return "Error: missing required parameter: url";
			}
			String url = args.getString("url");
			URI uri;
			try {
				uri = URI.create(url);
			} catch (RuntimeException e) {
				return "Error: invalid url: " + url;
			}
			String scheme = uri.getScheme();
			if (scheme == null
					|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
				return "Error: only http/https schemes are allowed: " + url;
			}

			String method = args.optString("method", "GET").toUpperCase();
			HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
				.timeout(this.timeout);

			for (Map.Entry<String, String> e : this.defaultHeaders.entrySet()) {
				reqBuilder.header(e.getKey(), e.getValue());
			}
			if (args.has("headers")) {
				JsonObject headers = args.getJsonObject("headers");
				for (String k : headers.keySet()) {
					reqBuilder.header(k, headers.getString(k));
				}
			}

			HttpRequest.BodyPublisher publisher;
			if ("POST".equals(method)) {
				publisher = HttpRequest.BodyPublishers.ofString(args.optString("body", ""));
			} else {
				if (!"GET".equals(method)) {
					method = "GET";
				}
				publisher = HttpRequest.BodyPublishers.noBody();
			}
			reqBuilder.method(method, publisher);

			HttpResponse<String> resp =
				this.client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
			String body = resp.body() == null ? "" : resp.body();
			if (body.length() > this.maxResponseLength) {
				body = body.substring(0, this.maxResponseLength) + "...[truncated]";
			}
			return body;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "Error: request interrupted";
		} catch (Exception e) {
			return "Error: http request failed: " + e.getMessage();
		}
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private Duration timeout = DEFAULT_TIMEOUT;
		private Map<String, String> defaultHeaders;
		private int maxResponseLength = DEFAULT_MAX_RESPONSE_LENGTH;

		private Builder() {
		}

		/**
		 * 设置连接/请求超时（默认 10s）。
		 *
		 * @param timeout 超时
		 * @return this
		 */
		public Builder timeout(Duration timeout) {
			if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
				this.timeout = timeout;
			}
			return this;
		}

		/**
		 * 设置默认请求头（每次请求都会附带）。
		 *
		 * @param defaultHeaders 默认头
		 * @return this
		 */
		public Builder defaultHeaders(Map<String, String> defaultHeaders) {
			if (defaultHeaders == null) {
				this.defaultHeaders = null;
			} else {
				this.defaultHeaders = new LinkedHashMap<>(defaultHeaders);
			}
			return this;
		}

		/**
		 * 设置响应体最大长度（默认 8000），超出截断。
		 *
		 * @param maxResponseLength 最大字符数
		 * @return this
		 */
		public Builder maxResponseLength(int maxResponseLength) {
			if (maxResponseLength > 0) {
				this.maxResponseLength = maxResponseLength;
			}
			return this;
		}

		/**
		 * 构建 HttpTool。
		 *
		 * @return HttpTool
		 */
		public HttpTool build() {
			return new HttpTool(this);
		}
	}
}
