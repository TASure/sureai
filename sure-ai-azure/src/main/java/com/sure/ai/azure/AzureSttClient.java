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

package com.sure.ai.azure;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;

/**
 * Azure AI Speech 短音频识别（STT/REST）客户端。
 *
 * <p>baseUrl 形如 {@code https://{resource}.cognitiveservices.azure.com}，
 * 鉴权头为 {@code Ocp-Apim-Subscription-Key}。协议：</p>
 * <ul>
 *   <li>端点：{@code POST /stt/speech/recognition/conversation/cognitiveservices/v1
 *       ?language={lang}&format=simple}；</li>
 *   <li>请求体：音频二进制直接放 Body，{@code Content-Type} 缺省
 *       {@code audio/wav; codecs=audio/pcm; samplerate=16000}；</li>
 *   <li>响应：{@code {RecognitionStatus:"Success", DisplayText:"..."}}。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public class AzureSttClient extends AbstractAiClient {

	/** 默认音频 Content-Type。 */
	public static final String DEFAULT_CONTENT_TYPE =
		"audio/wav; codecs=audio/pcm; samplerate=16000";

	/**
	 * 构造客户端。
	 *
	 * @param config 配置：apiKey 为 Speech 资源密钥，baseUrl 为
	 *               {@code https://{resource}.cognitiveservices.azure.com}
	 */
	public AzureSttClient(AiConfig config) {
		super(config);
	}

	/**
	 * 客户端名称。
	 *
	 * @return "azure-stt"
	 */
	public String name() {
		return "azure-stt";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Ocp-Apim-Subscription-Key", cfg.apiKey());
	}

	/**
	 * 语音识别：POST 二进制音频，解析 DisplayText。
	 *
	 * @param request STT 请求
	 * @return 语音识别响应（转写文本）
	 */
	public SttResponse transcribe(SttRequest request) {
		String language = request.language() == null || request.language().isBlank()
			? "zh-CN" : request.language();
		String contentType = request.contentType() == null || request.contentType().isBlank()
			? DEFAULT_CONTENT_TYPE : request.contentType();
		String path = "/stt/speech/recognition/conversation/cognitiveservices/v1"
			+ "?language=" + URLEncoder.encode(language, StandardCharsets.UTF_8)
			+ "&format=simple";
		HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(resolve(path)))
			.timeout(this.config.timeout())
			.header("Content-Type", contentType)
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofByteArray(request.audioData()));
		applyAuth(rb, this.config);
		HttpResponse<String> resp;
		try {
			resp = this.httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiTimeoutException("azure stt request failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("azure stt interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			throw mapError(status, resp.body());
		}
		JsonObject o = Json.parse(resp.body()).getAsJsonObject();
		String statusStr = o.optString("RecognitionStatus", "");
		if (!"Success".equals(statusStr)) {
			throw new AiException("azure stt failed: " + statusStr + " " + resp.body());
		}
		return SttResponse.ofText(o.optString("DisplayText", ""));
	}

	/** 拼接完整 URL。 */
	private String resolve(String path) {
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
}
