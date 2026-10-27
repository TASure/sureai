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

package com.sure.ai.evals.trace;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.evals.EvalException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;

/**
 * 单次对话交互的录制记录（不可变）：一次 {@code chat()} 调用的请求与响应成对落盘。
 *
 * <p>定位：{@code sure-ai-evals} 模块的<b>通用对话录制</b>，面向 golden dataset 工作流——
 * 录制真实后端响应 → 离线回放重跑评估 → 指标回归比对。与 {@code sure-ai-rag} 的
 * {@code RagTrace / TraceSerializer} 不同：后者记录 RAG 问答轨迹（问题 + 召回上下文文档 +
 * 参考答案），服务于 RAG 专用指标（忠实度 / 上下文精度等）；本类只做「请求 ⇄ 响应」
 * 成对快照，不感知上下文文档与参考答案，二者互不依赖、不可互相替代。</p>
 *
 * <p>JSON 往返保留字段：</p>
 * <ul>
 *   <li>请求：{@code model}、消息列表（{@code role} / {@code content} / {@code toolCallId} /
 *       {@code reasoningContent} / {@code toolCalls} 的 id/函数名/参数 JSON）、
 *       {@code temperature/maxTokens/topP/stream/user}；</li>
 *   <li>响应：{@code id/model}、候选列表（{@code index/finishReason/message}）、
 *       {@code TokenUsage}（prompt/completion/total）。</li>
 * </ul>
 *
 * <p>边界（刻意不保留）：多模态 {@code parts}（图片等二进制）、请求侧 tools/toolChoice/
 * responseFormat 等平台透传对象、响应侧 {@code rawJson} 与联网来源——评估只依赖文本与
 * 工具调用核对，这些字段不参与打分，落盘会引入二进制与第三方类型依赖。</p>
 *
 * @param id                记录 ID（对应用例 ID 或序号）
 * @param recordedAtMillis  录制时间戳（epoch 毫秒）
 * @param request           对话请求
 * @param response          对话响应
 * @author sureai
 * @since 2.8.0
 */
public record EvalTrace(String id, long recordedAtMillis, ChatRequest request,
		ChatResponse response) {

	/**
	 * 紧凑构造器：非空校验。
	 */
	public EvalTrace {
		if (request == null) {
			throw new EvalException("EvalTrace.request 不能为 null");
		}
		if (response == null) {
			throw new EvalException("EvalTrace.response 不能为 null");
		}
	}

	/**
	 * 序列化为 JSON 文本（单行，可直接作为 JSONL 一行）。
	 *
	 * @param trace 记录
	 * @return JSON 文本
	 */
	public static String toJson(EvalTrace trace) {
		if (trace == null) {
			throw new EvalException("trace 不能为 null");
		}
		return Json.stringify(traceToObject(trace));
	}

	/**
	 * 序列化为 JSONL 行（语义同 {@link #toJson(EvalTrace)}，显式表达「一行一条」用途）。
	 *
	 * @param trace 记录
	 * @return JSON 文本
	 */
	public static String toJsonLine(EvalTrace trace) {
		return toJson(trace);
	}

	/**
	 * 从 JSON 文本解析记录。
	 *
	 * @param json JSON 文本
	 * @return 记录
	 * @throws EvalException JSON 非法、缺必填字段或角色未知时抛出
	 */
	public static EvalTrace parse(String json) {
		if (json == null || json.isBlank()) {
			throw new EvalException("trace JSON 不能为空");
		}
		JsonElement el;
		try {
			el = Json.parse(json);
		} catch (RuntimeException e) {
			throw new EvalException("trace JSON 解析失败: " + e.getMessage(), e);
		}
		if (!el.isObject()) {
			throw new EvalException("trace JSON 必须是对象");
		}
		return objectToTrace(el.getAsJsonObject());
	}

	private static JsonObject traceToObject(EvalTrace trace) {
		JsonObject obj = Json.object();
		obj.set("id", trace.id());
		obj.set("recordedAtMillis", trace.recordedAtMillis());
		obj.set("request", requestToObject(trace.request()));
		obj.set("response", responseToObject(trace.response()));
		return obj;
	}

	private static JsonObject requestToObject(ChatRequest request) {
		JsonObject obj = Json.object();
		obj.set("model", request.model());
		JsonArray messages = Json.array();
		for (ChatMessage message : request.messages()) {
			messages.add(messageToObject(message));
		}
		obj.set("messages", messages);
		if (request.temperature() != null) {
			obj.set("temperature", request.temperature());
		}
		if (request.maxTokens() != null) {
			obj.set("maxTokens", request.maxTokens());
		}
		if (request.topP() != null) {
			obj.set("topP", request.topP());
		}
		obj.set("stream", request.stream());
		if (request.user() != null) {
			obj.set("user", request.user());
		}
		return obj;
	}

	private static JsonObject messageToObject(ChatMessage message) {
		JsonObject obj = Json.object();
		obj.set("role", message.role().value());
		if (message.content() != null) {
			obj.set("content", message.content());
		}
		if (message.toolCallId() != null) {
			obj.set("toolCallId", message.toolCallId());
		}
		if (message.reasoningContent() != null) {
			obj.set("reasoningContent", message.reasoningContent());
		}
		List<ToolCall> toolCalls = message.toolCalls();
		if (toolCalls != null && !toolCalls.isEmpty()) {
			JsonArray calls = Json.array();
			for (ToolCall call : toolCalls) {
				JsonObject callObj = Json.object();
				if (call.id() != null) {
					callObj.set("id", call.id());
				}
				callObj.set("name", call.name());
				if (call.argumentsJson() != null) {
					callObj.set("argumentsJson", call.argumentsJson());
				}
				calls.add(callObj);
			}
			obj.set("toolCalls", calls);
		}
		return obj;
	}

	private static JsonObject responseToObject(ChatResponse response) {
		JsonObject obj = Json.object();
		if (response.id() != null) {
			obj.set("id", response.id());
		}
		obj.set("model", response.model());
		JsonArray choices = Json.array();
		for (Choice choice : response.choices()) {
			JsonObject choiceObj = Json.object();
			choiceObj.set("index", choice.index());
			if (choice.finishReason() != null) {
				choiceObj.set("finishReason", choice.finishReason());
			}
			choiceObj.set("message", messageToObject(choice.message()));
			choices.add(choiceObj);
		}
		obj.set("choices", choices);
		TokenUsage usage = response.usage();
		if (usage != null) {
			JsonObject usageObj = Json.object();
			usageObj.set("promptTokens", usage.promptTokens());
			usageObj.set("completionTokens", usage.completionTokens());
			usageObj.set("totalTokens", usage.totalTokens());
			obj.set("usage", usageObj);
		}
		return obj;
	}

	private static EvalTrace objectToTrace(JsonObject obj) {
		String id = obj.optString("id", null);
		long recordedAt = obj.optLong("recordedAtMillis", 0L);
		ChatRequest request = objectToRequest(optRequiredObject(obj, "request"));
		ChatResponse response = objectToResponse(optRequiredObject(obj, "response"));
		return new EvalTrace(id, recordedAt, request, response);
	}

	private static JsonObject optRequiredObject(JsonObject obj, String key) {
		if (!obj.has(key) || obj.get(key).isNull()) {
			throw new EvalException("trace 缺 " + key + " 字段");
		}
		return obj.getJsonObject(key);
	}

	private static JsonArray optArray(JsonObject obj, String key) {
		return obj.has(key) ? obj.getJsonArray(key) : null;
	}

	private static ChatRequest objectToRequest(JsonObject obj) {
		String model = obj.optString("model", null);
		if (model == null || model.isBlank()) {
			throw new EvalException("trace.request 缺 model");
		}
		JsonArray messagesArr = optArray(obj, "messages");
		if (messagesArr == null || messagesArr.isEmpty()) {
			throw new EvalException("trace.request 缺 messages");
		}
		List<ChatMessage> messages = new ArrayList<>(messagesArr.size());
		for (int i = 0; i < messagesArr.size(); i++) {
			messages.add(objectToMessage(messagesArr.getJsonObject(i)));
		}
		ChatRequest.Builder builder = ChatRequest.builder()
			.model(model)
			.messages(messages);
		if (obj.has("temperature")) {
			builder.temperature(obj.getDouble("temperature"));
		}
		if (obj.has("maxTokens")) {
			builder.maxTokens(obj.getInt("maxTokens"));
		}
		if (obj.has("topP")) {
			builder.topP(obj.getDouble("topP"));
		}
		if (obj.has("stream")) {
			builder.stream(obj.getBoolean("stream"));
		}
		String user = obj.optString("user", null);
		if (user != null) {
			builder.user(user);
		}
		return builder.build();
	}

	private static ChatMessage objectToMessage(JsonObject obj) {
		String roleValue = obj.optString("role", null);
		if (roleValue == null) {
			throw new EvalException("trace message 缺 role");
		}
		Role role;
		try {
			role = Role.fromValue(roleValue);
		} catch (RuntimeException e) {
			throw new EvalException("trace message 角色未知: " + roleValue, e);
		}
		String content = obj.optString("content", null);
		String toolCallId = obj.optString("toolCallId", null);
		String reasoning = obj.optString("reasoningContent", null);
		List<ToolCall> toolCalls = null;
		JsonArray calls = optArray(obj, "toolCalls");
		if (calls != null && !calls.isEmpty()) {
			toolCalls = new ArrayList<>(calls.size());
			for (int i = 0; i < calls.size(); i++) {
				JsonObject call = calls.getJsonObject(i);
				toolCalls.add(ToolCall.of(call.optString("id", null),
					call.optString("name", null), call.optString("argumentsJson", null)));
			}
		}
		return ChatMessage.of(role, content, null, null, toolCallId, toolCalls, reasoning);
	}

	private static ChatResponse objectToResponse(JsonObject obj) {
		String id = obj.optString("id", null);
		String model = obj.optString("model", null);
		JsonArray choicesArr = optArray(obj, "choices");
		List<Choice> choices = new ArrayList<>();
		if (choicesArr != null) {
			for (int i = 0; i < choicesArr.size(); i++) {
				JsonObject choiceObj = choicesArr.getJsonObject(i);
				choices.add(Choice.of(choiceObj.getInt("index"),
					objectToMessage(choiceObj.getJsonObject("message")),
					choiceObj.optString("finishReason", null)));
			}
		}
		TokenUsage usage = null;
		JsonObject usageObj = obj.has("usage") ? obj.getJsonObject("usage") : null;
		if (usageObj != null) {
			usage = TokenUsage.of(usageObj.optInt("promptTokens", 0),
				usageObj.optInt("completionTokens", 0), usageObj.optInt("totalTokens", 0));
		}
		return ChatResponse.of(id, model, choices, usage, null);
	}
}
