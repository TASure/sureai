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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * <p><b>SSRF 防护（默认开启）：</b>在发送请求前解析目标 host 的所有 IP 地址，
 * 拒绝回环、私有段、链路本地、任意本地、多播及 IPv4-mapped IPv6 内网地址。
 * 可通过 {@link Builder#ssrfProtection(boolean)} 关闭（仅限内网部署场景）。
 * 注意：JDK HttpClient 发送请求时会再次解析 DNS，存在理论上的 TOCTOU 窗口；
 * 详见 {@link SSRFGuard} 类 JavaDoc。</p>
 *
 * <p><b>重定向：</b>JDK HttpClient 默认 {@code followRedirects=NEVER}，
 * 不自动跟随重定向，因此不存在"重定向到内网地址"的风险。</p>
 *
 * <p><b>OOM 防护：</b>响应体通过 {@link HttpResponse.BodyHandlers#ofInputStream()}
 * 流式读取，最多缓冲 {@code maxResponseLength * 4 + 1} 字节后即截断，
 * 不会将整个响应体读入内存。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class HttpTool implements ToolHandler {

	/** 工具名。 */
	public static final String TOOL_NAME = "http_request";

	private static final int DEFAULT_MAX_RESPONSE_LENGTH = 8000;
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
	/** UTF-8 最坏情况下每个字符占 4 字节。 */
	private static final int UTF8_MAX_BYTES_PER_CHAR = 4;

	private final HttpClient client;
	private final Map<String, String> defaultHeaders;
	private final int maxResponseLength;
	private final Duration timeout;
	private final boolean ssrfProtection;

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
		this.ssrfProtection = b.ssrfProtection;
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

			// SSRF 防护：解析 host 并校验所有 IP
			if (this.ssrfProtection) {
				String ssrfError = SSRFGuard.check(uri);
				if (ssrfError != null) {
					return "Error: " + ssrfError + ": " + url;
				}
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

			// 流式读取响应体，限长防 OOM
			HttpResponse<InputStream> resp =
				this.client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
			return readLimited(resp.body(), this.maxResponseLength);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "Error: request interrupted";
		} catch (Exception e) {
			return "Error: http request failed: " + e.getMessage();
		}
	}

	/**
	 * 从输入流流式读取响应体，最多保留 {@code maxChars} 个字符。
	 *
	 * <p>为防止 UTF-8 多字节字符导致实际字节数远超字符数，按字节限制为
	 * {@code maxChars * 4}（UTF-8 最坏 4 字节/字符）。超过后立即关闭流，
	 * 不将全量响应读入内存。</p>
	 *
	 * @param in       响应体输入流
	 * @param maxChars 最大保留字符数
	 * @return 截断后的字符串（超长时追加 "...[truncated]"）
	 * @throws IOException 读取失败
	 */
	private static String readLimited(InputStream in, int maxChars) throws IOException {
		int maxBytes = maxChars * UTF8_MAX_BYTES_PER_CHAR;
		byte[] buf = new byte[8192];
		ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxBytes, 8192) + 16);
		int total = 0;
		boolean truncated = false;
		try (in) {
			int n;
			while ((n = in.read(buf)) != -1) {
				if (total + n > maxBytes) {
					int canWrite = maxBytes - total;
					if (canWrite > 0) {
						baos.write(buf, 0, canWrite);
					}
					truncated = true;
					break;
				}
				baos.write(buf, 0, n);
				total += n;
			}
		}
		String s = baos.toString(StandardCharsets.UTF_8);
		if (truncated && s.length() > maxChars) {
			s = s.substring(0, maxChars) + "...[truncated]";
		}
		return s;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private Duration timeout = DEFAULT_TIMEOUT;
		private Map<String, String> defaultHeaders;
		private int maxResponseLength = DEFAULT_MAX_RESPONSE_LENGTH;
		private boolean ssrfProtection = true;

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
		 * 开关 SSRF 防护（默认开启）。
		 *
		 * <p>开启后，请求前会解析目标 host 的所有 IP 地址，拒绝回环、私有段、
		 * 链路本地、任意本地、多播等内网/保留地址。仅在确认需要访问内网服务的
		 * 部署场景下关闭。</p>
		 *
		 * @param enabled true 开启 SSRF 防护；false 关闭
		 * @return this
		 */
		public Builder ssrfProtection(boolean enabled) {
			this.ssrfProtection = enabled;
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
