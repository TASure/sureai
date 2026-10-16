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

package com.sure.ai.ingest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;
import com.sure.tool.lang.Assert;

/**
 * URL 加载器：通过 JDK {@link HttpClient} GET 下载后按扩展名 / Content-Type 路由。
 *
 * <p>仅支持 {@code http}/{@code https} scheme；下载字节后按 URI 路径扩展名选择解析器，
 * 无扩展名时按 Content-Type 兜底推断。非 2xx 状态码抛出 {@link IllegalStateException}。
 * 本地路径请用 {@link FileSystemLoader}。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class URLLoader implements DocumentLoader {

	/** 默认连接超时（秒）。 */
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
	/** 默认请求超时（秒）。 */
	public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private final ParserRegistry registry;
	private final HttpClient httpClient;
	private final Duration requestTimeout;

	private URLLoader(Builder builder) {
		this.registry = builder.registry;
		this.httpClient = builder.httpClient;
		this.requestTimeout = builder.requestTimeout;
	}

	/**
	 * 创建含内置解析器（TXT/MD/HTML/PDF）的默认加载器。
	 *
	 * @return 默认 URL 加载器
	 */
	public static URLLoader createDefault() {
		return builder().build();
	}

	/**
	 * 创建 Builder，可注入自定义 {@link HttpClient} 与超时、追加解析器。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public List<Document> load(Path path) {
		throw new IllegalStateException("URLLoader 仅支持网络 URI，本地路径请用 FileSystemLoader: " + path);
	}

	@Override
	public List<Document> load(URI uri) {
		Assert.notNull(uri, "uri 不能为 null");
		String scheme = uri.getScheme();
		if (scheme == null
				|| (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
			throw new IllegalStateException("URLLoader 仅支持 http/https: " + uri);
		}
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(uri)
					.timeout(requestTimeout)
					.GET()
					.build();
			HttpResponse<byte[]> response = httpClient.send(request,
					HttpResponse.BodyHandlers.ofByteArray());
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IllegalStateException("HTTP 请求失败，状态码 " + status + "，URL=" + uri);
			}
			byte[] body = response.body() == null ? new byte[0] : response.body();
			String contentType = response.headers().firstValue("content-type").orElse("");
			String filename = fileNameFromUri(uri);
			if (ParserRegistry.extensionOf(filename) == null) {
				filename = ParserRegistry.extensionByContentType(contentType);
			}
			return registry.route(body, filename, uri.toString(), contentType);
		} catch (IOException e) {
			throw new IllegalStateException("下载 URL 失败: " + uri, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("下载 URL 被中断: " + uri, e);
		}
	}

	/**
	 * 从 URI 路径取末段文件名（无路径时返回空串）。
	 *
	 * @param uri URI
	 * @return 文件名
	 */
	private static String fileNameFromUri(URI uri) {
		String path = uri.getPath();
		if (path == null || path.isEmpty()) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	/**
	 * {@link URLLoader} 构造器。
	 */
	public static final class Builder {

		private final ParserRegistry registry = ParserRegistry.withDefaults();
		private HttpClient httpClient = defaultClient();
		private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

		private Builder() {
		}

		/**
		 * 注入自定义 {@link HttpClient}（便于测试与覆盖超时/连接池）。
		 *
		 * @param client HTTP 客户端，不允许为 null
		 * @return this
		 */
		public Builder httpClient(HttpClient client) {
			Assert.notNull(client, "client 不能为 null");
			this.httpClient = client;
			return this;
		}

		/**
		 * 设置请求超时。
		 *
		 * @param timeout 请求超时，不允许为 null
		 * @return this
		 */
		public Builder requestTimeout(Duration timeout) {
			Assert.notNull(timeout, "timeout 不能为 null");
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * 追加一个格式解析器。
		 *
		 * @param parser 解析器
		 * @return this
		 */
		public Builder register(DocumentParser parser) {
			Assert.notNull(parser, "parser 不能为 null");
			registry.register(parser);
			return this;
		}

		/**
		 * 构建加载器。
		 *
		 * @return URL 加载器
		 */
		public URLLoader build() {
			return new URLLoader(this);
		}
	}

	/**
	 * 默认 {@link HttpClient}：10s 连接超时、跟随重定向。
	 *
	 * @return 默认客户端
	 */
	private static HttpClient defaultClient() {
		return HttpClient.newBuilder()
				.connectTimeout(DEFAULT_CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}
}
