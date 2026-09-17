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

package com.sure.ai.rag.loader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sure.ai.rag.model.Document;
import com.sure.tool.lang.Assert;

/**
 * URL 文档加载器：通过 JDK {@link HttpClient} GET 拉取网页，做基础 HTML 去标签提取纯文本。
 *
 * <p>提取流程：</p>
 * <ol>
 *   <li>移除 {@code <script>...</script>} 与 {@code <style>...</style>} 块（含内容）；</li>
 *   <li>移除所有 HTML 标签（{@code <[^>]+>}）；</li>
 *   <li>解码常用 HTML 实体（{@code &amp; &lt; &gt; &quot; &#39; &nbsp;} 与数字实体）；</li>
 *   <li>合并多余空白（连续换行/空格压缩为单个空格）。</li>
 * </ol>
 *
 * <p><b>注意</b>：JDK 21 无内置 HTML 解析器，本实现为基于正则的基础去标签，
 * 不处理复杂嵌套、畸形标签与 JS 渲染页面，建议仅用于简单静态 HTML 页面。</p>
 *
 * <p>元数据包含 {@code source}（URL）、{@code loaded_at}（ISO-8601 时间戳）、
 * {@code content_type}（响应 Content-Type，若有）。HTTP 状态码非 2xx 抛出
 * {@link IllegalStateException}（含状态码）。连接超时默认 10s、请求超时默认 30s；
 * 注入自定义 {@link HttpClient} 可覆盖连接超时，请求超时通过 {@link #requestTimeout} 覆盖。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public class UrlDocumentLoader implements DocumentLoader {

	/** 元数据键：来源 */
	public static final String META_SOURCE = "source";
	/** 元数据键：加载时间戳 */
	public static final String META_LOADED_AT = "loaded_at";
	/** 元数据键：Content-Type */
	public static final String META_CONTENT_TYPE = "content_type";

	/** 默认连接超时（秒） */
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
	/** 默认请求超时（秒） */
	public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static final Pattern SCRIPT_STYLE_BLOCK =
			Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1>",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final URI uri;
	private final HttpClient httpClient;
	private final Duration requestTimeout;

	/**
	 * 以 URL 字符串构造，使用默认超时与共享 {@link HttpClient}。
	 *
	 * @param url 目标 URL，不允许为空白
	 */
	public UrlDocumentLoader(String url) {
		Assert.notBlank(url, "url 不能为空");
		try {
			this.uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("非法 URL: " + url, e);
		}
		this.httpClient = defaultClient();
		this.requestTimeout = DEFAULT_REQUEST_TIMEOUT;
	}

	/**
	 * 以 {@link URI} 构造，使用默认超时与共享 {@link HttpClient}。
	 *
	 * @param uri 目标 URI，不允许为 null
	 */
	public UrlDocumentLoader(URI uri) {
		this(uri, defaultClient(), DEFAULT_REQUEST_TIMEOUT);
	}

	/**
	 * 注入自定义 {@link HttpClient}（便于测试与覆盖连接池/超时配置）。
	 *
	 * @param uri 目标 URI，不允许为 null
	 * @param httpClient 自定义 HTTP 客户端，不允许为 null
	 */
	public UrlDocumentLoader(URI uri, HttpClient httpClient) {
		this(uri, httpClient, DEFAULT_REQUEST_TIMEOUT);
	}

	/**
	 * 全参构造器。
	 *
	 * @param uri 目标 URI，不允许为 null
	 * @param httpClient HTTP 客户端，不允许为 null
	 * @param requestTimeout 请求超时，不允许为 null
	 */
	public UrlDocumentLoader(URI uri, HttpClient httpClient, Duration requestTimeout) {
		Assert.notNull(uri, "uri 不能为 null");
		Assert.notNull(httpClient, "httpClient 不能为 null");
		Assert.notNull(requestTimeout, "requestTimeout 不能为 null");
		this.uri = uri;
		this.httpClient = httpClient;
		this.requestTimeout = requestTimeout;
	}

	/**
	 * 创建默认 {@link HttpClient}：10s 连接超时。
	 *
	 * @return 默认 HTTP 客户端
	 */
	private static HttpClient defaultClient() {
		return HttpClient.newBuilder()
				.connectTimeout(DEFAULT_CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	@Override
	public List<Document> load() {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(uri)
					.timeout(requestTimeout)
					.GET()
					.build();
			HttpResponse<String> response = httpClient.send(request,
					HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IllegalStateException("HTTP 请求失败，状态码 " + status + "，URL=" + uri);
			}
			String html = response.body() == null ? "" : response.body();
			String text = extractPlainText(html).trim();
			if (text.isEmpty()) {
				return new ArrayList<>(0);
			}
			Map<String, String> metadata = new LinkedHashMap<>();
			metadata.put(META_SOURCE, uri.toString());
			metadata.put(META_LOADED_AT, Instant.now().toString());
			response.headers().firstValue("content-type")
					.ifPresent(ct -> metadata.put(META_CONTENT_TYPE, ct));
			List<Document> documents = new ArrayList<>(1);
			documents.add(Document.of(uri.toString(), text, metadata));
			return documents;
		} catch (IOException e) {
			throw new IllegalStateException("下载 URL 失败: " + uri, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("下载 URL 被中断: " + uri, e);
		}
	}

	/**
	 * 返回目标 URI。
	 *
	 * @return 目标 URI
	 */
	public URI uri() {
		return uri;
	}

	/**
	 * 基础 HTML 去标签提取纯文本。
	 *
	 * @param html 原始 HTML
	 * @return 纯文本
	 */
	private static String extractPlainText(String html) {
		if (html == null || html.isEmpty()) {
			return "";
		}
		// 1. 移除 script/style 块（含内容）
		String noBlocks = SCRIPT_STYLE_BLOCK.matcher(html).replaceAll(" ");
		// 2. 移除所有标签
		String noTags = HTML_TAG.matcher(noBlocks).replaceAll(" ");
		// 3. 解码 HTML 实体
		String decoded = decodeEntities(noTags);
		// 4. 合并多余空白
		return WHITESPACE.matcher(decoded).replaceAll(" ").trim();
	}

	/**
	 * 解码常用 HTML 实体与数字实体。
	 *
	 * @param input 去标签后的文本
	 * @return 解码后文本
	 */
	private static String decodeEntities(String input) {
		String result = input;
		result = result.replace("&nbsp;", " ");
		result = result.replace("&quot;", "\"");
		result = result.replace("&#39;", "'");
		result = result.replace("&lt;", "<");
		result = result.replace("&gt;", ">");
		result = result.replace("&amp;", "&");
		// 数字实体：十进制 &#NNN; 与十六进制 &#xHHH;
		Matcher matcher = NUMERIC_ENTITY.matcher(result);
		StringBuffer sb = new StringBuffer(result.length());
		while (matcher.find()) {
			boolean hex = "x".equals(matcher.group(1));
			int codePoint;
			try {
				codePoint = Integer.parseInt(matcher.group(2), hex ? 16 : 10);
			} catch (NumberFormatException e) {
				matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
				continue;
			}
			matcher.appendReplacement(sb,
					Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}
}
