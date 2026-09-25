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
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatRequest;
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
 * <p>P2 平台特定能力：</p>
 * <ul>
 *   <li><b>思考模式</b>：用 {@code enable_thinking}（布尔）+ {@code thinking_budget}（int），
 *   不支持 OpenAI 的 {@code reasoning_effort}；{@code reasoningEffort} 会被近似映射为
 *   {@code enable_thinking=true} 与对应预算。思考内容仍在 {@code reasoning_content}，core 已解析。</li>
 *   <li><b>Grounding 联网</b>：用顶层 {@code enable_search: true} 布尔开关，不注入
 *   {@code web_search} 工具；联网来源在 {@code message.annotations}，core 已解析。</li>
 *   <li><b>模型列表</b>：兼容模式 {@code GET /compatible-mode/v1/models} 支持，直接继承 core。</li>
 *   <li><b>微调</b>：DashScope 原生微调协议（{@code /api/v1/fine-tunes}）与 OpenAI
 *   fine_tuning/jobs 请求/响应差异较大，本期未单独适配；可通过 {@code extra()} 透传或后续
 *   按原生协议扩展。</li>
 * </ul>
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

	// ==================== 思考模式与 Grounding 联网（通义协议差异） ====================

	/**
	 * 通义对话请求序列化：在 core 的 OpenAI 兼容序列化基础上做两处平台改写。
	 *
	 * <ol>
	 *   <li><b>思考模式</b>：通义用 {@code enable_thinking: true}（布尔）与
	 *   {@code thinking_budget: int}（思考 token 预算），<b>不支持</b> OpenAI 的
	 *   {@code reasoning_effort}。因此先移除 core 写入的 {@code reasoning_effort}，
	 *   再按 {@link ChatRequest#thinkingConfig()} 或 {@code reasoningEffort} 映射。</li>
	 *   <li><b>联网 Grounding</b>：通义用顶层 {@code enable_search: true} 布尔开关，
	 *   <b>不支持</b> OpenAI 的 {@code web_search} 工具。因此 grounding 非 null 时，
	 *   移除 core 自动注入的 {@code {"type":"web_search"}} 工具，改写为 {@code enable_search}。</li>
	 * </ol>
	 *
	 * <p>响应侧无需改动：通义思考内容在 {@code choices[].message.reasoning_content}、
	 * 联网来源在 {@code message.annotations}，均由 core 解析。</p>
	 *
	 * @param req    对话请求
	 * @param stream 是否流式
	 * @return 通义协议请求体
	 */
	@Override
	protected JsonObject buildChatBody(ChatRequest req, boolean stream) {
		JsonObject body = super.buildChatBody(req, stream);
		applyThinking(body, req);
		applyGrounding(body, req);
		return body;
	}

	/** 思考模式改写：移除 reasoning_effort，按 thinkingConfig/reasoningEffort 写入通义字段。 */
	private void applyThinking(JsonObject body, ChatRequest req) {
		Object thinkingCfg = req.thinkingConfig();
		String effort = req.reasoningEffort();
		if (thinkingCfg == null && effort == null) {
			return;
		}
		// 通义不支持 reasoning_effort，core 已写入则移除。
		body.remove("reasoning_effort");
		if (thinkingCfg != null) {
			applyThinkingConfig(body, thinkingCfg);
			return;
		}
		// reasoningEffort 非 null：近似映射为 enable_thinking=true + thinking_budget。
		body.put("enable_thinking", true);
		Integer budget = effortToBudget(effort);
		if (budget != null) {
			body.put("thinking_budget", budget);
		}
	}

	/** 解析 thinkingConfig：Boolean true / String "true" → enable_thinking=true；JsonObject/Map 含 thinking_budget 则写入。 */
	private static void applyThinkingConfig(JsonObject body, Object cfg) {
		if (Boolean.TRUE.equals(cfg)) {
			body.put("enable_thinking", true);
			return;
		}
		if (cfg instanceof String s) {
			if ("true".equalsIgnoreCase(s)) {
				body.put("enable_thinking", true);
			}
			return;
		}
		Integer budget = null;
		if (cfg instanceof JsonObject jo) {
			if (jo.has("thinking_budget")) {
				budget = jo.getInt("thinking_budget");
			}
		} else if (cfg instanceof Map<?, ?> m && m.get("thinking_budget") instanceof Number n) {
			budget = n.intValue();
		}
		body.put("enable_thinking", true);
		if (budget != null) {
			body.put("thinking_budget", budget);
		}
	}

	/** OpenAI reasoning_effort → 通义 thinking_budget（token 数近似映射）。 */
	private static Integer effortToBudget(String effort) {
		if (effort == null) {
			return null;
		}
		return switch (effort) {
			case "minimal" -> 512;
			case "low" -> 1024;
			case "high" -> 16384;
			default -> 4096; // medium 及未知值
		};
	}

	/** Grounding 改写：grounding 非 null 时写入 enable_search=true，并移除 core 注入的 web_search 工具。 */
	private static void applyGrounding(JsonObject body, ChatRequest req) {
		if (req.grounding() == null) {
			return;
		}
		body.put("enable_search", true);
		if (!body.has("tools")) {
			return;
		}
		JsonArray tools = body.getJsonArray("tools");
		JsonArray filtered = Json.array();
		for (int i = 0; i < tools.size(); i++) {
			JsonElement el = tools.get(i);
			if (el.isObject()) {
				JsonObject t = el.getAsJsonObject();
				if ("web_search".equals(t.optString("type", null))) {
					continue;
				}
			}
			filtered.add(el);
		}
		if (filtered.isEmpty()) {
			body.remove("tools");
		} else {
			body.put("tools", filtered);
		}
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
	 * baseUrl 为空时用默认地址替换（其余全部字段通过 {@link AiConfig#withBaseUrl} 原样保留）。
	 *
	 * @param config      原始配置
	 * @param defaultBase 默认 baseUrl
	 * @return 补齐 baseUrl 后的配置
	 */
	static AiConfig applyDefaultBaseUrl(AiConfig config, String defaultBase) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(defaultBase);
	}
}
