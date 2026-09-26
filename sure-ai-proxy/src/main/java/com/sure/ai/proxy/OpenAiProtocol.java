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

package com.sure.ai.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sure.ai.exception.AiException;
import com.sure.ai.gateway.RequestContext;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;

/**
 * OpenAI 兼容协议编解码：在 OpenAI HTTP 报文与 core 模型之间双向转换。
 *
 * <p>全部静态方法、无状态；JSON 一律走 {@link Json} 门面，不引入任何第三方库。
 * 包私有，仅供 {@link SureAiProxy} 内部使用。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
final class OpenAiProtocol {

	private OpenAiProtocol() {
		throw new AssertionError("no instances");
	}

	/**
	 * 解析请求体为 JSON 对象；非法 JSON 抛 {@link AiException}（映射为 400）。
	 *
	 * @param body 请求体字符串
	 * @return JSON 对象
	 */
	static JsonObject parseBody(String body) {
		JsonElement el = Json.parse(body == null ? "" : body);
		if (!el.isObject()) {
			throw new AiException("Request body must be a JSON object");
		}
		return el.getAsJsonObject();
	}

	/**
	 * 把 OpenAI chat 请求体转换为 core {@link ChatRequest}。
	 *
	 * @param body         OpenAI 请求体
	 * @param defaultModel 默认模型（请求未带 model 时兜底）
	 * @param tenantId     鉴权解析出的租户 ID，写入 extra["tenantId"]
	 * @return core 对话请求
	 */
	static ChatRequest toCoreChatRequest(JsonObject body, String defaultModel, String tenantId) {
		String model = body.optString("model", defaultModel);
		if (model == null || model.isBlank()) {
			model = defaultModel;
		}
		boolean stream = bool(body, "stream");
		List<ChatMessage> messages = toCoreMessages(body);

		ChatRequest.Builder b = ChatRequest.builder()
			.model(model)
			.messages(messages)
			.stream(stream);
		if (body.has("temperature")) {
			b.temperature(body.getDouble("temperature"));
		}
		if (body.has("max_tokens")) {
			b.maxTokens(body.getInt("max_tokens"));
		}
		if (body.has("top_p")) {
			b.topP(body.getDouble("top_p"));
		}
		String user = body.optString("user", null);
		if (user != null) {
			b.user(user);
		}
		b.extra(RequestContext.EXTRA_TENANT_ID, tenantId);
		return b.build();
	}

	/** 解析 OpenAI messages 数组为 core 消息列表。 */
	private static List<ChatMessage> toCoreMessages(JsonObject body) {
		List<ChatMessage> out = new ArrayList<>();
		JsonElement msgsEl = body.get("messages");
		if (msgsEl == null || !msgsEl.isArray()) {
			throw new AiException("'messages' must be a non-empty array");
		}
		JsonArray arr = msgsEl.getAsJsonArray();
		for (JsonElement el : arr) {
			JsonObject m = el.getAsJsonObject();
			String roleRaw = m.optString("role", "user");
			Role role = Role.fromValue(roleRaw);
			String content = contentAsString(m);
			out.add(ChatMessage.of(role, content, null, null, null, null));
		}
		if (out.isEmpty()) {
			throw new AiException("'messages' must not be empty");
		}
		return out;
	}

	/** content 可能是字符串或结构化数组，统一转成字符串透传。 */
	private static String contentAsString(JsonObject m) {
		JsonElement c = m.get("content");
		if (c == null || c.isNull()) {
			return "";
		}
		return c.isString() ? c.getAsString() : Json.stringify(c);
	}

	/** 安全读取布尔字段（缺失/非布尔均视为 false）。 */
	private static boolean bool(JsonObject body, String key) {
		JsonElement el = body.get(key);
		return el != null && el.isBoolean() && el.getAsBoolean();
	}

	/**
	 * 把 core 对话响应序列化为 OpenAI chat.completion 报文。
	 *
	 * @param resp core 响应
	 * @return OpenAI JSON 字符串
	 */
	static String chatCompletionJson(ChatResponse resp) {
		JsonObject root = Json.object();
		root.set("id", "chatcmpl-" + (resp.id() != null ? resp.id() : UUID.randomUUID()));
		root.set("object", "chat.completion");
		root.set("created", System.currentTimeMillis() / 1000L);
		root.set("model", resp.model() == null ? "" : resp.model());

		JsonArray choices = Json.array();
		for (Choice c : resp.choices()) {
			JsonObject ch = Json.object();
			ch.set("index", c.index());
			JsonObject msg = Json.object();
			msg.set("role", c.message().role().value());
			msg.set("content", c.message().content());
			ch.set("message", msg);
			ch.set("finish_reason", c.finishReason());
			choices.add(ch);
		}
		root.set("choices", choices);
		root.set("usage", usageJson(resp.usage()));
		return Json.stringify(root);
	}

	/** 把 core 流式分片序列化为 OpenAI chunk 报文（不含 {@code data: } 前缀）。 */
	static String streamChunkJson(ChatStreamChunk chunk, String model) {
		JsonObject root = Json.object();
		root.set("id", chunk.id() != null ? chunk.id() : "chatcmpl-stream");
		root.set("object", "chat.completion.chunk");
		root.set("created", System.currentTimeMillis() / 1000L);
		root.set("model", model == null ? "" : model);

		JsonArray choices = Json.array();
		JsonObject ch = Json.object();
		ch.set("index", 0);
		JsonObject delta = Json.object();
		if (chunk.role() != null) {
			delta.set("role", chunk.role().value());
		}
		if (chunk.deltaText() != null) {
			delta.set("content", chunk.deltaText());
		}
		ch.set("delta", delta);
		ch.set("finish_reason", chunk.finishReason());
		choices.add(ch);
		root.set("choices", choices);
		return Json.stringify(root);
	}

	/** /v1/models 列表报文。 */
	static String modelsJson(List<String> models) {
		JsonObject root = Json.object();
		root.set("object", "list");
		JsonArray data = Json.array();
		for (String m : models) {
			JsonObject mo = Json.object();
			mo.set("id", m);
			mo.set("object", "model");
			mo.set("owned_by", "sureai");
			data.add(mo);
		}
		root.set("data", data);
		return Json.stringify(root);
	}

	/** /v1/embeddings 响应报文。 */
	static String embeddingsJson(EmbeddingResponse resp) {
		JsonObject root = Json.object();
		root.set("object", "list");
		JsonArray data = Json.array();
		int i = 0;
		for (float[] vec : resp.embeddings()) {
			JsonObject eo = Json.object();
			eo.set("object", "embedding");
			eo.set("index", i++);
			JsonArray emb = Json.array();
			for (float v : vec) {
				emb.add((double) v);
			}
			eo.set("embedding", emb);
			data.add(eo);
		}
		root.set("data", data);
		root.set("model", resp.model() == null ? "" : resp.model());
		root.set("usage", usageJson(resp.usage()));
		return Json.stringify(root);
	}

	/** OpenAI 错误报文。 */
	static String errorJson(String message, String type, String code) {
		JsonObject err = Json.object();
		err.set("message", message);
		err.set("type", type);
		err.set("code", code);
		JsonObject root = Json.object();
		root.set("error", err);
		return Json.stringify(root);
	}

	/** 构造 usage 对象（prompt/completion/total）。 */
	private static JsonObject usageJson(TokenUsage usage) {
		JsonObject u = Json.object();
		if (usage == null) {
			u.set("prompt_tokens", 0);
			u.set("completion_tokens", 0);
			u.set("total_tokens", 0);
		} else {
			u.set("prompt_tokens", usage.promptTokens());
			u.set("completion_tokens", usage.completionTokens());
			u.set("total_tokens", usage.totalTokens());
		}
		return u;
	}
}
