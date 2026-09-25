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

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 请求构建器（包内可见协作组件）。
 *
 * <p>从 {@link AbstractAiClient} 拆出：统一封装 JSON POST / GET / multipart 三类请求的构建，
 * 并按固定顺序执行钩子链——</p>
 *
 * <ol>
 *   <li>{@link AbstractAiClient#applyAuth}（子类挂载鉴权头）</li>
 *   <li>{@link AiConfig#extraHeaders()}（全局额外头）</li>
 *   <li>{@link AbstractAiClient#signRequest}（子类挂载签名头，跳过 Host）</li>
 * </ol>
 *
 * <p>本类与 {@link AbstractAiClient} 同包，故可直接访问其 {@code protected} 的
 * {@code applyAuth}/{@code signRequest}/{@code config}；对 {@code signRequest} 的调用经虚方法
 * 分派，Bedrock 等子类的 SigV4 覆写依然生效。每次重试重建请求时都会重新走一遍钩子链，
 * 时间戳类签名随重试刷新——与重构前一致。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
final class RequestBuilder {

	private final AbstractAiClient client;

	private final AiConfig config;

	RequestBuilder(AbstractAiClient client) {
		this.client = client;
		this.config = client.config;
	}

	/**
	 * 构建 JSON POST 请求（Content-Type: application/json）。
	 *
	 * @param url     完整 URL
	 * @param payload JSON 请求体字符串
	 * @return 请求构建器（尚未 build）
	 */
	HttpRequest.Builder post(String url, String payload) {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
		applyAuthAndHeaders(b, "POST", url, payload);
		return b;
	}

	/**
	 * 构建 GET 请求（Accept: application/json，无 body）。
	 *
	 * @param url 完整 URL
	 * @return 请求构建器（尚未 build）
	 */
	HttpRequest.Builder get(String url) {
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Accept", "application/json")
			.GET();
		applyAuthAndHeaders(rb, "GET", url, "");
		return rb;
	}

	/**
	 * 构建 multipart/form-data POST 请求。
	 *
	 * @param url      完整 URL
	 * @param boundary 分隔符
	 * @param body     已拼装的 multipart 字节
	 * @return 请求构建器（尚未 build）
	 */
	HttpRequest.Builder multipart(String url, String boundary, byte[] body) {
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		applyAuthAndHeaders(rb, "POST", url, new String(body, StandardCharsets.UTF_8));
		return rb;
	}

	/**
	 * 按固定顺序应用钩子链：applyAuth → extraHeaders → signRequest（跳过 Host）。
	 */
	private void applyAuthAndHeaders(HttpRequest.Builder rb, String method, String url, String body) {
		this.client.applyAuth(rb, this.config);
		for (Map.Entry<String, String> e : this.config.extraHeaders().entrySet()) {
			rb.header(e.getKey(), e.getValue());
		}
		applySignedHeaders(rb, this.client.signRequest(method, url, body));
	}

	/**
	 * 把 {@link AbstractAiClient#signRequest} 返回的签名头应用到 builder，
	 * 跳过 Host（JDK HttpClient 禁止显式设置 Host）。
	 */
	private static void applySignedHeaders(HttpRequest.Builder rb, Map<String, String> signed) {
		for (Map.Entry<String, String> e : signed.entrySet()) {
			if ("Host".equalsIgnoreCase(e.getKey())) {
				continue;
			}
			rb.header(e.getKey(), e.getValue());
		}
	}
}
