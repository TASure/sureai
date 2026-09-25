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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.cache.CacheStore;
import com.sure.ai.client.cache.ChatCacheKey;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.CacheControl;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.GroundingSource;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.Role;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * Chat 能力域策略：非流式 {@code /chat/completions} 的请求体构建与响应解析。
 *
 * <p>职责边界：消息/工具/多模态片段的序列化、{@code usage} 回传、联网来源提取，以及带缓存的
 * 非流式调用流。流式（SSE）分片解析见 {@link StreamCompatStrategy}。</p>
 *
 * <p>请求体构建入口 {@link #buildBody} 被 {@link OpenAiCompatClient} 的 protected
 * {@code buildChatBody} 委托——平台子类（如通义）覆写 {@code buildChatBody} 时先调用
 * {@code super.buildChatBody} 再做平台差异化改写，故本类不直接承担入口，仅提供默认实现。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
final class ChatCompatStrategy {

	/** 持有外层客户端引用，用于虚方法分发（buildChatBody）与受保护传输/路径字段访问。 */
	private final OpenAiCompatClient client;

	/**
	 * @param client 外层 OpenAI 兼容客户端
	 */
	ChatCompatStrategy(OpenAiCompatClient client) {
		this.client = client;
	}

	/** 非流式 chat 入口（含缓存命中/回写）。 */
	ChatResponse chat(ChatRequest request) {
		CacheStore cache = this.client.config().cacheStore();
		if (cache != null && !request.stream()) {
			return chatWithCache(request, cache);
		}
		JsonObject body = this.client.buildChatBody(request, false);
		var result = this.client.transportPostRaw(this.client.chatPath, body);
		return parseChatResponse(result.json(), result.rawBody());
	}

	/**
	 * 带缓存的非流式 chat：命中直接返回（不触发网络/指标/重试），未命中走网络并回写。
	 *
	 * <p>设计说明：缓存命中意味着没有真实 HTTP 请求，因此不触发 MetricsCollector 的
	 * onRequestStart/Success、也不计数重试；这是刻意的取舍——缓存命中不属于一次真实的模型调用。
	 * 错误响应在 transportPostRaw 阶段即抛出，不会进入缓存写入路径。</p>
	 */
	private ChatResponse chatWithCache(ChatRequest request, CacheStore cache) {
		String key = ChatCacheKey.of(request);
		ChatResponse cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		JsonObject body = this.client.buildChatBody(request, false);
		var result = this.client.transportPostRaw(this.client.chatPath, body);
		ChatResponse response = parseChatResponse(result.json(), result.rawBody());
		long ttlMillis = this.client.config().cacheTtl() == null ? -1L
			: this.client.config().cacheTtl().toMillis();
		cache.put(key, response, ttlMillis);
		return response;
	}

	/**
	 * 构造对话请求体（默认实现，由外层 protected {@code buildChatBody} 委托）。
	 *
	 * @param req    对话请求
	 * @param stream 是否流式
	 * @return 请求体 JSON
	 */
	JsonObject buildBody(ChatRequest req, boolean stream) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		JsonArray messages = Json.array();
		for (ChatMessage m : req.messages()) {
			messages.add(serializeMessage(m));
		}
		body.put("messages", messages);
		CompatJson.putIfNotNull(body, "temperature", req.temperature());
		CompatJson.putIfNotNull(body, "max_tokens", req.maxTokens());
		CompatJson.putIfNotNull(body, "top_p", req.topP());
		if (req.stop() != null) {
			body.put("stop", Json.toElement(req.stop()));
		}
		body.put("stream", stream);
		CompatJson.putIfNotNull(body, "user", req.user());
		JsonArray tools = Json.array();
		if (req.tools() != null && !req.tools().isEmpty()) {
			for (ToolSpec spec : req.tools()) {
				JsonObject t = Json.object();
				t.put("type", "function");
				t.set("function", serializeFunction(spec.function()));
				tools.add(t);
			}
		}
		injectGroundingTool(tools, req.grounding());
		if (!tools.isEmpty()) {
			body.put("tools", tools);
		}
		if (req.toolChoice() != null) {
			body.put("tool_choice", Json.toElement(req.toolChoice()));
		}
		CompatJson.putIfNotNull(body, "presence_penalty", req.presencePenalty());
		CompatJson.putIfNotNull(body, "frequency_penalty", req.frequencyPenalty());
		CompatJson.putIfNotNull(body, "seed", req.seed());
		if (req.responseFormat() != null) {
			body.put("response_format", Json.toElement(req.responseFormat()));
		}
		CompatJson.putIfNotNull(body, "reasoning_effort", req.reasoningEffort());
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	/**
	 * 注入联网 Grounding 工具：grounding 为 "web_search" 字符串时注入
	 * {@code {"type":"web_search"}} 工具；为 JsonObject/Map 时直接作为工具注入；为 null 时不注入。
	 */
	private static void injectGroundingTool(JsonArray tools, Object grounding) {
		if (grounding == null) {
			return;
		}
		if ("web_search".equals(grounding)) {
			JsonObject tool = Json.object();
			tool.put("type", "web_search");
			tools.add(tool);
		} else {
			tools.add(Json.toElement(grounding));
		}
	}

	/** 序列化为 Map 形式的函数定义。 */
	private static Map<String, Object> serializeFunction(ToolFunction fn) {
		Map<String, Object> map = new LinkedHashMap<>();
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
			CacheControl cc = tp.cacheControl();
			if (cc != null && cc.type() != null) {
				JsonObject ccObj = Json.object();
				ccObj.put("type", cc.type());
				o.put("cache_control", ccObj);
			}
		} else if (p instanceof ImagePart ip) {
			o.put("type", "image_url");
			JsonObject inner = Json.object();
			inner.put("url", ip.resolvedUrl());
			o.put("image_url", inner);
		} else if (p instanceof DocumentPart dp) {
			if (dp.fileId() != null) {
				o.put("type", "input_file");
				JsonObject inner = Json.object();
				inner.put("file_id", dp.fileId());
				o.put("input_file", inner);
			} else {
				o.put("type", "input_file");
				JsonObject inner = Json.object();
				if (dp.name() != null) {
					inner.put("filename", dp.name());
				}
				if (dp.mimeType() != null) {
					inner.put("mime_type", dp.mimeType());
				}
				if (dp.data() != null) {
					String mime = dp.mimeType() == null ? "application/pdf" : dp.mimeType();
					inner.put("file_data", "data:" + mime + ";base64," + dp.data());
				}
				o.put("input_file", inner);
			}
		}
		return o;
	}

	/** 解析对话响应。 */
	ChatResponse parseChatResponse(JsonObject resp, String rawJson) {
		String id = resp.optString("id", null);
		String model = resp.optString("model", null);
		List<Choice> choices = new ArrayList<>();
		List<GroundingSource> groundingSources = new ArrayList<>();
		JsonArray arr = resp.getJsonArray("choices");
		for (int i = 0; i < arr.size(); i++) {
			JsonObject c = arr.getJsonObject(i);
			JsonObject msg = c.getJsonObject("message");
			choices.add(Choice.of(c.optInt("index", 0), parseMessage(msg),
				c.optString("finish_reason", null)));
			collectGroundingSources(msg, groundingSources);
		}
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
				u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
			this.client.notifyUsage(model, usage.promptTokens(), usage.completionTokens(),
				usage.totalTokens());
		}
		return ChatResponse.of(id, model, choices, usage, groundingSources, rawJson);
	}

	/** 从 message.annotations 中提取联网来源（url_citation / url）。 */
	private static void collectGroundingSources(JsonObject msg, List<GroundingSource> out) {
		if (msg == null || !msg.has("annotations")) {
			return;
		}
		JsonArray annotations = msg.getJsonArray("annotations");
		for (int i = 0; i < annotations.size(); i++) {
			JsonObject ann = annotations.getJsonObject(i);
			String type = ann.optString("type", null);
			if ("url_citation".equals(type) && ann.has("url_citation")) {
				JsonObject uc = ann.getJsonObject("url_citation");
				out.add(GroundingSource.of(uc.optString("title", null),
					uc.optString("url", null), ann.optString("quoted_text", null)));
			} else if (ann.has("url")) {
				out.add(GroundingSource.of(ann.optString("title", null),
					ann.optString("url", null), ann.optString("quoted_text", null)));
			}
		}
	}

	/** 解析响应中的 message 对象。 */
	private ChatMessage parseMessage(JsonObject msg) {
		Role role = msg.has("role") ? Role.fromValue(msg.getString("role")) : null;
		String content = msg.has("content") && !msg.get("content").isNull()
			? msg.getString("content") : null;
		String reasoning = msg.has("reasoning_content") && !msg.get("reasoning_content").isNull()
			? msg.getString("reasoning_content") : null;
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
		return ChatMessage.of(role, content, null, null, null, calls, reasoning);
	}
}
