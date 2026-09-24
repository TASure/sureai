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

package com.sure.ai.cohere;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.ModelsClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.Model;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;

/**
 * Cohere v2 客户端（独立协议，非 OpenAI 兼容）。
 *
 * <p>自研实现，不复用 OpenAI 兼容引擎。差异点：</p>
 * <ul>
 *   <li>鉴权：请求头 {@code Authorization: Bearer <apiKey>}。</li>
 *   <li>对话接口 {@code POST /chat}：请求体为 {@code {model, messages:[{role, content}],
 *       stream, temperature, max_tokens}}；<b>无 choices 字段</b>，正文在
 *       {@code message.content[]}（按 {@code type="text"} 累加 {@code text}）。</li>
 *   <li>用量在 {@code usage.tokens.input_tokens} / {@code usage.tokens.output_tokens}。</li>
 *   <li>向量接口 {@code POST /embed}：{@code input_type} 必填（默认 {@code search_document}），
 *       请求体 {@code {model, input_type, texts, embedding_types:["float"]}}，
 *       向量在 {@code embeddings.float}。</li>
 *   <li>流式：SSE 以 {@code type=content-delta} 投递增量（增量文本在
 *       {@code delta.message.content.text}），以 {@code type=message-end} 事件结束，
 *       <b>无 {@code [DONE]} 标志</b>。</li>
 *   <li>Cohere v2 不提供模型列表 API，{@link #listModels()} 直接抛 {@link AiException}。</li>
 * </ul>
 *
 * <p>默认 baseUrl：{@code https://api.cohere.com/v2}。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class CohereClient extends AbstractAiClient implements AiClient, EmbeddingClient, ModelsClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://api.cohere.com/v2";

	/** 对话接口相对路径。 */
	public static final String CHAT_PATH = "/chat";

	/** 向量接口相对路径。 */
	public static final String EMBED_PATH = "/embed";

	/**
	 * 构造客户端。
	 *
	 * @param config 配置：apiKey 为 Cohere API Key
	 */
	public CohereClient(AiConfig config) {
		super(withDefaults(config));
	}

	@Override
	public String name() {
		return "cohere";
	}

	@Override
	protected void applyAuth(java.net.http.HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		JsonObject body = buildChatBody(request, false);
		PostResult result = doPostRaw(CHAT_PATH, body);
		return parseChatResponse(result.json(), result.rawBody(), request.model());
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		JsonObject body = buildChatBody(request, true);
		doPostStream(CHAT_PATH, body, el -> consumer.accept(parseStreamChunk(el)));
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		JsonObject body = Json.object();
		body.put("model", request.model());
		body.put("input_type", "search_document");
		JsonArray texts = Json.array();
		for (String s : request.input()) {
			texts.add(s);
		}
		body.set("texts", texts);
		JsonArray embedTypes = Json.array();
		embedTypes.add("float");
		body.set("embedding_types", embedTypes);
		JsonObject resp = doPost(EMBED_PATH, body);
		return parseEmbeddingResponse(resp, request.model());
	}

	/**
	 * Cohere v2 不提供模型列表 API。
	 *
	 * @return 永不返回
	 * @throws AiException 始终抛出
	 */
	@Override
	public List<Model> listModels() {
		throw new AiException("Cohere v2 does not provide a models list API");
	}

	// ==================== 内部解析 ====================

	/** 构造对话请求体。 */
	private JsonObject buildChatBody(ChatRequest req, boolean stream) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		JsonArray messages = Json.array();
		for (ChatMessage m : req.messages()) {
			JsonObject o = Json.object();
			o.put("role", m.role().value());
			o.put("content", m.content() == null ? "" : m.content());
			messages.add(o);
		}
		body.set("messages", messages);
		body.put("stream", stream);
		if (req.temperature() != null) {
			body.put("temperature", req.temperature());
		}
		if (req.maxTokens() != null) {
			body.put("max_tokens", req.maxTokens());
		}
		return body;
	}

	/** 解析非流式对话响应：正文在 message.content[].text。 */
	private ChatResponse parseChatResponse(JsonObject resp, String raw, String model) {
		String id = resp.optString("id", null);
		StringBuilder text = new StringBuilder();
		if (resp.has("message")) {
			JsonObject message = resp.getJsonObject("message");
			if (message.has("content")) {
				JsonArray content = message.getJsonArray("content");
				for (int i = 0; i < content.size(); i++) {
					JsonObject part = content.getJsonObject(i);
					if (part.optString("type", "text").equals("text")) {
						text.append(part.optString("text", ""));
					}
				}
			}
		}
		ChatMessage out = ChatMessage.of(Role.ASSISTANT, text.toString(), null, null, null, null);
		List<Choice> choices = List.of(Choice.of(0, out, "stop"));
		TokenUsage usage = parseUsage(resp);
		return ChatResponse.of(id, model, choices, usage, raw);
	}

	/** 解析流式分片：content-delta 取增量，message-end 结束。 */
	private ChatStreamChunk parseStreamChunk(JsonElement el) {
		JsonObject o = el.getAsJsonObject();
		String type = o.optString("type", "");
		if ("content-delta".equals(type)) {
			String delta = null;
			if (o.has("delta")) {
				JsonObject d = o.getJsonObject("delta");
				if (d.has("message")) {
					JsonObject msg = d.getJsonObject("message");
					if (msg.has("content")) {
						JsonObject content = msg.getJsonObject("content");
						delta = content.optString("text", null);
					}
				}
			}
			return ChatStreamChunk.of(o.optString("id", null), null, delta, null, null);
		}
		if ("message-end".equals(type)) {
			return ChatStreamChunk.of(o.optString("id", null), null, null, null, "stop");
		}
		return ChatStreamChunk.of(o.optString("id", null), null, null, null, null);
	}

	/** 解析向量响应：embeddings.float 为向量矩阵。 */
	private EmbeddingResponse parseEmbeddingResponse(JsonObject resp, String requestModel) {
		String model = resp.optString("model", requestModel);
		List<float[]> embeddings = new ArrayList<>();
		if (resp.has("embeddings")) {
			JsonObject embeddingsObj = resp.getJsonObject("embeddings");
			if (embeddingsObj.has("float")) {
				JsonArray matrix = embeddingsObj.getJsonArray("float");
				for (int i = 0; i < matrix.size(); i++) {
					JsonArray row = matrix.getJsonArray(i);
					float[] vec = new float[row.size()];
					for (int j = 0; j < row.size(); j++) {
						vec[j] = (float) row.getDouble(j);
					}
					embeddings.add(vec);
				}
			}
		}
		TokenUsage usage = parseUsage(resp);
		return EmbeddingResponse.of(model, embeddings, usage);
	}

	/** 解析 usage.tokens.{input_tokens, output_tokens}。 */
	private TokenUsage parseUsage(JsonObject resp) {
		if (!resp.has("usage")) {
			return null;
		}
		JsonObject usage = resp.getJsonObject("usage");
		if (!usage.has("tokens")) {
			return null;
		}
		JsonObject tokens = usage.getJsonObject("tokens");
		int input = tokens.optInt("input_tokens", 0);
		int output = tokens.optInt("output_tokens", 0);
		return TokenUsage.of(input, output, input + output);
	}

	/** baseUrl 为空时补默认地址，其余配置原样保留。 */
	private static AiConfig withDefaults(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(config.apiKey())
			.baseUrl(DEFAULT_BASE_URL)
			.timeout(config.timeout())
			.connectTimeout(config.connectTimeout())
			.maxRetries(config.maxRetries());
		if (config.proxy() != null && !config.proxy().isBlank()) {
			b.proxy(config.proxy());
		}
		if (config.organization() != null && !config.organization().isBlank()) {
			b.organization(config.organization());
		}
		for (Map.Entry<String, String> e : config.extraHeaders().entrySet()) {
			b.extraHeader(e.getKey(), e.getValue());
		}
		return b.build();
	}
}
