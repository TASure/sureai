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

package com.sure.ai.qwen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.Segment;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.Word;

/**
 * 阿里云百炼通义千问（DashScope）OpenAI 兼容模式客户端。
 *
 * <p>默认 baseUrl 为 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，
 * 兼容引擎拼接 {@code /chat/completions} 与 {@code /embeddings}；
 * 鉴权为 {@code Authorization: Bearer <sk-...>}。支持对话、SSE 流式与向量。</p>
 *
 * <p>TTS（CosyVoice）与 STT（Qwen-ASR）走 DashScope 原生协议：TTS 返回 JSON 含音频 URL，
 * STT 以 chat/completions 的 {@code input_audio} 多模态内容上报 base64 音频。</p>
 *
 * <p>官方文档：
 * <a href="https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope">通过 OpenAI 接口调用千问模型</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class QwenClient extends OpenAiCompatClient implements EmbeddingClient {

	/** DashScope 兼容模式默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

	/** 原生 TTS（CosyVoice）合成端点。 */
	private static final String TTS_PATH = "/api/v1/services/audio/tts/SpeechSynthesizer";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public QwenClient(AiConfig config) {
		super(applyDefaultBaseUrl(config, DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "qwen";
	}

	// ==================== 语音合成 TTS（CosyVoice，返回音频 URL） ====================

	@Override
	public TtsResponse synthesize(TtsRequest request) {
		JsonObject input = Json.object();
		input.put("text", request.input());
		input.put("voice", request.voice());
		String format = request.responseFormat() == null ? "mp3" : request.responseFormat();
		input.put("format", format);
		input.put("sample_rate", request.sampleRate() == null
			? Integer.valueOf(22050) : request.sampleRate());
		input.put("volume", request.volume() == null
			? Double.valueOf(50.0) : request.volume());
		input.put("rate", request.speed() == null
			? Double.valueOf(1.0) : request.speed());
		JsonObject body = Json.object();
		body.put("model", request.model());
		body.put("input", input);
		JsonResp result = postJson(dashScopeRoot() + TTS_PATH, body);
		JsonObject output = result.json().getJsonObject("output");
		JsonObject audio = output.getJsonObject("audio");
		String audioUrl = audio.optString("url", null);
		return TtsResponse.ofUrl(audioUrl, format, result.raw());
	}

	// ==================== 语音识别 STT（Qwen-ASR，chat/completions input_audio） ====================

	@Override
	public SttResponse transcribe(SttRequest request) {
		String mime = request.contentType() == null ? "audio/wav" : request.contentType();
		String dataUri = "data:" + mime + ";base64,"
			+ Base64.getEncoder().encodeToString(request.audioData());
		JsonObject inputAudio = Json.object();
		inputAudio.put("data", dataUri);
		JsonArray content = Json.array();
		JsonObject part = Json.object();
		part.put("type", "input_audio");
		part.put("input_audio", inputAudio);
		content.add(part);
		JsonObject message = Json.object();
		message.put("role", "user");
		message.put("content", content);
		JsonArray messages = Json.array();
		messages.add(message);
		JsonObject body = Json.object();
		body.put("model", request.model());
		body.put("messages", messages);
		PostResult result = doPostRaw(this.chatPath, body);
		JsonObject choice0 = result.json().getJsonArray("choices").getJsonObject(0);
		String text = choice0.getJsonObject("message").optString("content", "");
		return SttResponse.of(text, null, null, List.<Segment>of(), List.<Word>of(), result.rawBody());
	}

	/**
	 * 由兼容模式 baseUrl 推导 DashScope 原生根地址（剥离 {@code /compatible-mode} 后缀）。
	 *
	 * @return 原生根地址
	 */
	private String dashScopeRoot() {
		String base = this.config.baseUrl();
		int idx = base.indexOf("/compatible-mode");
		if (idx >= 0) {
			return base.substring(0, idx);
		}
		return base;
	}

	/**
	 * 向指定完整 URL 发送 JSON POST（用于 TTS 原生端点，区别于 doPostRaw 的相对路径解析）。
	 *
	 * @param url  完整 URL
	 * @param body 请求体
	 * @return 解析结果（含原始报文）
	 */
	private JsonResp postJson(String url, JsonObject body) {
		String payload = Json.stringify(body);
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
		applyAuth(rb, this.config);
		for (Map.Entry<String, String> e : this.config.extraHeaders().entrySet()) {
			rb.header(e.getKey(), e.getValue());
		}
		HttpResponse<String> resp;
		try {
			resp = this.httpClient.send(rb.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiTimeoutException("qwen tts request failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("qwen tts request interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			throw mapError(status, resp.body());
		}
		return new JsonResp(parseJson(resp.body()), resp.body());
	}

	/** JSON 响应与原始报文体。 */
	private record JsonResp(JsonObject json, String raw) {
	}

	/**
	 * baseUrl 为空时用默认地址重建配置。
	 *
	 * @param config      原始配置
	 * @param defaultBase 默认 baseUrl
	 * @return 补齐 baseUrl 后的配置
	 */
	static AiConfig applyDefaultBaseUrl(AiConfig config, String defaultBase) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(config.apiKey())
			.baseUrl(defaultBase)
			.timeout(config.timeout())
			.connectTimeout(config.connectTimeout())
			.proxy(config.proxy())
			.organization(config.organization())
			.maxRetries(config.maxRetries());
		for (Map.Entry<String, String> e : config.extraHeaders().entrySet()) {
			b.extraHeader(e.getKey(), e.getValue());
		}
		return b.build();
	}
}
