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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;

/**
 * Azure AI Speech 语音合成（TTS）客户端。
 *
 * <p>与 Azure OpenAI 不同服务域：baseUrl 形如 {@code https://{region}.tts.speech.microsoft.com}，
 * 鉴权头为 {@code Ocp-Apim-Subscription-Key}（非 api-key/Bearer）。协议为 SSML XML：</p>
 * <ul>
 *   <li>端点：{@code POST /cognitiveservices/v1}；</li>
 *   <li>必需头：{@code Content-Type: application/ssml+xml}、
 *       {@code X-Microsoft-OutputFormat}（默认 {@code audio-24khz-48kbitrate-mono-mp3}）、
 *       {@code User-Agent: sureai}；</li>
 *   <li>请求体：{@code <speak version='1.0' xml:lang='zh-CN'><voice name='...'>文本</voice></speak>}；</li>
 *   <li>成功：200 直接返回二进制音频流。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public class AzureTtsClient extends AbstractAiClient {

	/** User-Agent。 */
	public static final String USER_AGENT = "sureai";

	/**
	 * 构造客户端。
	 *
	 * @param config 配置：apiKey 为 Speech 资源密钥，baseUrl 为
	 *               {@code https://{region}.tts.speech.microsoft.com}
	 */
	public AzureTtsClient(AiConfig config) {
		super(config);
	}

	/**
	 * 客户端名称。
	 *
	 * @return "azure-tts"
	 */
	public String name() {
		return "azure-tts";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Ocp-Apim-Subscription-Key", cfg.apiKey());
	}

	/**
	 * 语音合成：构造 SSML 并 POST，返回二进制音频。
	 *
	 * @param request TTS 请求
	 * @return 语音合成响应（二进制音频）
	 */
	public TtsResponse synthesize(TtsRequest request) {
		String outputFormat = mapOutputFormat(request.responseFormat());
		String xml = buildSsml(request);
		HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(resolve("/cognitiveservices/v1")))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/ssml+xml")
			.header("X-Microsoft-OutputFormat", outputFormat)
			.header("User-Agent", USER_AGENT)
			.POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8));
		applyAuth(rb, this.config);
		HttpResponse<byte[]> resp;
		try {
			resp = this.httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (IOException ex) {
			throw new AiTimeoutException("azure tts request failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("azure tts interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			throw mapError(status, new String(resp.body(), StandardCharsets.UTF_8));
		}
		return TtsResponse.ofAudio(resp.body(), formatOf(outputFormat));
	}

	/** 拼接完整 URL（与 AbstractAiClient.resolveUrl 同逻辑）。 */
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

	/** 构造 SSML：xml:lang 从 voice 前缀推导（如 zh-CN-XiaoxiaoNeural → zh-CN）。 */
	private static String buildSsml(TtsRequest request) {
		String voice = request.voice();
		String lang = deriveLang(voice);
		return "<speak version='1.0' xml:lang='" + lang + "'>"
			+ "<voice name='" + voice + "'>"
			+ escapeXml(request.input())
			+ "</voice></speak>";
	}

	/** 从音色名推导语言码：取前两段（xx-XX），失败回退 zh-CN。 */
	private static String deriveLang(String voice) {
		if (voice != null) {
			String[] parts = voice.split("-");
			if (parts.length >= 2) {
				return parts[0] + "-" + parts[1];
			}
		}
		return "zh-CN";
	}

	/** XML 特殊字符转义。 */
	private static String escapeXml(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '<':
					sb.append("&lt;");
					break;
				case '>':
					sb.append("&gt;");
					break;
				case '&':
					sb.append("&amp;");
					break;
				case '\'':
					sb.append("&apos;");
					break;
				case '"':
					sb.append("&quot;");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	/** responseFormat 映射到 Azure X-Microsoft-OutputFormat。 */
	private static String mapOutputFormat(String format) {
		if (format == null || format.isBlank()) {
			return AzureModels.TTS_OUTPUT_MP3;
		}
		String f = format.trim();
		if (f.startsWith("audio-") || f.startsWith("riff-")) {
			return f;
		}
		switch (f.toLowerCase()) {
			case "wav":
			case "wave":
				return "riff-pcm-16khz-16bit-mono-pcm";
			case "mp3":
			default:
				return AzureModels.TTS_OUTPUT_MP3;
		}
	}

	/** 从 output format 反推音频格式字符串。 */
	private static String formatOf(String outputFormat) {
		if (outputFormat.contains("mp3")) {
			return "mp3";
		}
		if (outputFormat.contains("pcm")) {
			return "pcm";
		}
		return "audio";
	}
}
