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

package com.sure.ai.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.ImageClient;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.ImageResult;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.Role;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * OpenAI 兼容协议客户端引擎。
 *
 * <p>实现 /chat/completions 与 /embeddings 的请求序列化与响应解析；平台子类通过覆盖
 * chatPath/embeddingsPath 与 baseUrl 适配具体服务。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class OpenAiCompatClient extends AbstractAiClient implements AiClient, EmbeddingClient, ImageClient {

	/** 对话接口路径，子类可覆盖。 */
	protected String chatPath = "/chat/completions";

	/** 向量接口路径，子类可覆盖。 */
	protected String embeddingsPath = "/embeddings";

	/** 图像生成接口路径，子类可覆盖。 */
	protected String imagesPath = "/images/generations";

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（baseUrl 由平台子类或调用方设置）
	 */
	public OpenAiCompatClient(AiConfig config) {
		super(config);
	}

	@Override
	public String name() {
		return "openai-compat";
	}

	@Override
	protected void applyAuth(java.net.http.HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
		if (cfg.organization() != null && !cfg.organization().isBlank()) {
			requestBuilder.header("OpenAI-Organization", cfg.organization());
		}
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		JsonObject body = buildChatBody(request, false);
		PostResult result = doPostRaw(this.chatPath, body);
		return parseChatResponse(result.json(), result.rawBody());
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		JsonObject body = buildChatBody(request, true);
		doPostStream(this.chatPath, body, el -> consumer.accept(parseChunk(el.getAsJsonObject())));
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		JsonObject body = Json.object();
		body.put("model", request.model());
		JsonArray input = Json.array();
		for (String s : request.input()) {
			input.add(s);
		}
		body.put("input", input);
		JsonObject resp = doPost(this.embeddingsPath, body);
		return parseEmbeddingResponse(resp);
	}

	@Override
	public ImageResponse generate(ImageRequest request) {
		JsonObject body = buildImageBody(request);
		PostResult result = doPostRaw(this.imagesPath, body);
		return parseImageResponse(result.json(), result.rawBody());
	}

	/** 构造图像生成请求体。 */
	private JsonObject buildImageBody(ImageRequest req) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		body.put("prompt", req.prompt());
		putIfNotNull(body, "n", req.n());
		putIfNotNull(body, "size", req.size());
		putIfNotNull(body, "quality", req.quality());
		putIfNotNull(body, "style", req.style());
		putIfNotNull(body, "response_format", req.responseFormat());
		putIfNotNull(body, "user", req.user());
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	/** 解析图像生成响应：{created, data:[{url, b64_json, revised_prompt}]}。 */
	private ImageResponse parseImageResponse(JsonObject resp, String rawJson) {
		long created = resp.optLong("created", 0L);
		List<ImageResult> results = new ArrayList<>();
		JsonArray data = resp.has("data") ? resp.getJsonArray("data") : null;
		if (data != null) {
			for (int i = 0; i < data.size(); i++) {
				JsonObject d = data.getJsonObject(i);
				results.add(ImageResult.of(
					d.optString("url", null),
					d.optString("b64_json", null),
					d.optString("revised_prompt", null)));
			}
		}
		return ImageResponse.of(created, results, rawJson);
	}

	/** 构造对话请求体。 */
	private JsonObject buildChatBody(ChatRequest req, boolean stream) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		JsonArray messages = Json.array();
		for (ChatMessage m : req.messages()) {
			messages.add(serializeMessage(m));
		}
		body.put("messages", messages);
		putIfNotNull(body, "temperature", req.temperature());
		putIfNotNull(body, "max_tokens", req.maxTokens());
		putIfNotNull(body, "top_p", req.topP());
		if (req.stop() != null) {
			body.put("stop", Json.toElement(req.stop()));
		}
		body.put("stream", stream);
		putIfNotNull(body, "user", req.user());
		if (req.tools() != null && !req.tools().isEmpty()) {
			JsonArray tools = Json.array();
			for (ToolSpec spec : req.tools()) {
				JsonObject t = Json.object();
				t.put("type", "function");
				t.set("function", serializeFunction(spec.function()));
				tools.add(t);
			}
			body.put("tools", tools);
		}
		if (req.toolChoice() != null) {
			body.put("tool_choice", Json.toElement(req.toolChoice()));
		}
		putIfNotNull(body, "presence_penalty", req.presencePenalty());
		putIfNotNull(body, "frequency_penalty", req.frequencyPenalty());
		putIfNotNull(body, "seed", req.seed());
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	/** 序列化为 Map 形式的函数定义。 */
	private Map<String, Object> serializeFunction(ToolFunction fn) {
		Map<String, Object> map = new java.util.LinkedHashMap<>();
		map.put("name", fn.name());
		if (fn.description() != null) {
			map.put("description", fn.description());
		}
		if (fn.parameters() != null) {
			map.put("parameters", Json.parse(fn.parameters()));
		}
		return map;
	}

	/** 序列化单条消息。 */
	private JsonObject serializeMessage(ChatMessage m) {
		JsonObject o = Json.object();
		o.put("role", m.role().value());
		if (m.parts() != null && !m.parts().isEmpty()) {
			JsonArray parts = Json.array();
			for (MessagePart p : m.parts()) {
				parts.add(serializePart(p));
			}
			o.put("content", parts);
		} else if (m.content() != null) {
			o.put("content", m.content());
		}
		if (m.name() != null) {
			o.put("name", m.name());
		}
		if (m.toolCallId() != null) {
			o.put("tool_call_id", m.toolCallId());
		}
		if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
			JsonArray calls = Json.array();
			for (ToolCall c : m.toolCalls()) {
				JsonObject co = Json.object();
				co.put("id", c.id());
				co.put("type", "function");
				JsonObject fn = Json.object();
				fn.put("name", c.name());
				fn.put("arguments", c.argumentsJson() == null ? "{}" : c.argumentsJson());
				co.put("function", fn);
				calls.add(co);
			}
			o.put("tool_calls", calls);
		}
		return o;
	}

	/** 序列化多模态片段。 */
	private JsonObject serializePart(MessagePart p) {
		JsonObject o = Json.object();
		if (p instanceof TextPart tp) {
			o.put("type", "text");
			o.put("text", tp.text());
		} else if (p instanceof ImagePart ip) {
			o.put("type", "image_url");
			JsonObject inner = Json.object();
			inner.put("url", ip.resolvedUrl());
			o.put("image_url", inner);
		}
		return o;
	}

	/** 非空字段写入。 */
	private static void putIfNotNull(JsonObject body, String key, Object value) {
		if (value != null) {
			body.put(key, Json.toElement(value));
		}
	}

	/** 解析对话响应。 */
	private ChatResponse parseChatResponse(JsonObject resp, String rawJson) {
		String id = resp.optString("id", null);
		String model = resp.optString("model", null);
		List<Choice> choices = new ArrayList<>();
		JsonArray arr = resp.getJsonArray("choices");
		for (int i = 0; i < arr.size(); i++) {
			JsonObject c = arr.getJsonObject(i);
			JsonObject msg = c.getJsonObject("message");
			choices.add(Choice.of(c.optInt("index", 0), parseMessage(msg),
				c.optString("finish_reason", null)));
		}
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
				u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
		}
		return ChatResponse.of(id, model, choices, usage, rawJson);
	}

	/** 解析响应中的 message 对象。 */
	private ChatMessage parseMessage(JsonObject msg) {
		Role role = msg.has("role") ? Role.fromValue(msg.getString("role")) : null;
		String content = msg.has("content") && !msg.get("content").isNull()
			? msg.getString("content") : null;
		List<ToolCall> calls = null;
		if (msg.has("tool_calls")) {
			calls = new ArrayList<>();
			JsonArray tc = msg.getJsonArray("tool_calls");
			for (int i = 0; i < tc.size(); i++) {
				JsonObject c = tc.getJsonObject(i);
				JsonObject fn = c.getJsonObject("function");
				calls.add(ToolCall.of(c.optString("id", null),
					fn.optString("name", null), fn.optString("arguments", null)));
			}
		}
		return ChatMessage.of(role, content, null, null, null, calls);
	}

	/** 解析流式分片。 */
	private ChatStreamChunk parseChunk(JsonObject chunk) {
		String id = chunk.optString("id", null);
		JsonArray choices = chunk.getJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			return ChatStreamChunk.of(id, null, null, null, null);
		}
		JsonObject c = choices.getJsonObject(0);
		JsonObject delta = c.has("delta") ? c.getJsonObject("delta") : null;
		Role role = null;
		String text = null;
		List<ToolCall> calls = null;
		if (delta != null) {
			if (delta.has("role") && !delta.get("role").isNull()) {
				role = Role.fromValue(delta.getString("role"));
			}
			if (delta.has("content") && !delta.get("content").isNull()) {
				text = delta.getString("content");
			}
			if (delta.has("tool_calls")) {
				calls = new ArrayList<>();
				JsonArray tc = delta.getJsonArray("tool_calls");
				for (int i = 0; i < tc.size(); i++) {
					JsonObject cc = tc.getJsonObject(i);
					JsonObject fn = cc.has("function") ? cc.getJsonObject("function") : null;
					calls.add(ToolCall.of(cc.optString("id", null),
						fn == null ? null : fn.optString("name", null),
						fn == null ? null : fn.optString("arguments", null)));
				}
			}
		}
		String finish = c.optString("finish_reason", null);
		return ChatStreamChunk.of(id, role, text, calls, finish);
	}

	/** 解析向量响应。 */
	private EmbeddingResponse parseEmbeddingResponse(JsonObject resp) {
		String model = resp.optString("model", null);
		JsonArray data = resp.getJsonArray("data");
		List<float[]> embeddings = new ArrayList<>();
		for (int i = 0; i < data.size(); i++) {
			JsonObject d = data.getJsonObject(i);
			JsonArray emb = d.getJsonArray("embedding");
			float[] vec = new float[emb.size()];
			for (int j = 0; j < emb.size(); j++) {
				vec[j] = (float) emb.getDouble(j);
			}
			embeddings.add(vec);
		}
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
				u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
		}
		return EmbeddingResponse.of(model, embeddings, usage);
	}
}
