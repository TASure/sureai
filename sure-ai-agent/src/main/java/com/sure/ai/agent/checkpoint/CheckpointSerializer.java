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

package com.sure.ai.agent.checkpoint;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.Role;
import com.sure.ai.model.ToolCall;
import com.sure.tool.lang.Assert;

/**
 * {@link AgentCheckpoint} 与 JSON 文本之间的双向序列化工具。
 *
 * <p>由于 {@link ChatMessage} 是 final 类（非 record），这里手写映射，仅持久化
 * 与编排恢复相关的字段：role / content / toolCallId / toolCalls（id/name/argumentsJson）。
 * 多模态 parts 与 reasoningContent 不参与检查点恢复。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class CheckpointSerializer {

	private CheckpointSerializer() {
		throw new AssertionError("No instances");
	}

	/**
	 * 序列化为 JSON 文本。
	 *
	 * @param checkpoint 检查点
	 * @return JSON 文本
	 */
	public static String toJson(AgentCheckpoint checkpoint) {
		Assert.notNull(checkpoint, "checkpoint must not be null");
		JsonObject root = Json.object();
		root.put("sessionId", checkpoint.sessionId());
		root.put("iteration", checkpoint.iteration());
		root.put("createdAtEpochMs", checkpoint.createdAtEpochMs());
		root.set("finalAnswer", checkpoint.finalAnswer());
		root.put("history", historyToJson(checkpoint.history()));
		root.put("metadata", checkpoint.metadata());
		return Json.stringify(root);
	}

	/**
	 * 从 JSON 文本反序列化。
	 *
	 * @param json JSON 文本
	 * @return 检查点
	 * @throws AiException JSON 非法或字段缺失时抛出
	 */
	public static AgentCheckpoint fromJson(String json) {
		Assert.notNull(json, "json must not be null");
		JsonElement el = Json.parse(json);
		if (el == null || !el.isObject()) {
			throw new AiException("checkpoint JSON 根节点不是对象");
		}
		JsonObject root = el.getAsJsonObject();
		String sessionId = root.getString("sessionId");
		int iteration = root.getInt("iteration");
		long created = root.optLong("createdAtEpochMs", 0L);
		String finalAnswer = root.optString("finalAnswer", null);
		JsonObject metadata = root.has("metadata") ? root.getJsonObject("metadata") : Json.object();
		List<ChatMessage> history = root.has("history")
			? jsonToHistory(root.getJsonArray("history"))
			: List.of();
		return new AgentCheckpoint(sessionId, history, iteration, finalAnswer, created, metadata);
	}

	/** 历史消息数组 → JSON 数组。 */
	private static JsonArray historyToJson(List<ChatMessage> history) {
		JsonArray arr = Json.array();
		for (ChatMessage m : history) {
			arr.add(messageToJson(m));
		}
		return arr;
	}

	/** 单条消息 → JSON 对象。 */
	private static JsonObject messageToJson(ChatMessage m) {
		JsonObject jo = Json.object();
		jo.put("role", m.role().value());
		if (m.content() != null) {
			jo.put("content", m.content());
		}
		if (m.toolCallId() != null) {
			jo.put("toolCallId", m.toolCallId());
		}
		List<ToolCall> calls = m.toolCalls();
		if (calls != null && !calls.isEmpty()) {
			JsonArray arr = Json.array();
			for (ToolCall c : calls) {
				JsonObject co = Json.object();
				co.put("id", c.id());
				co.put("name", c.name());
				co.set("argumentsJson", c.argumentsJson());
				arr.add(co);
			}
			jo.put("toolCalls", arr);
		}
		return jo;
	}

	/** JSON 数组 → 历史消息列表。 */
	private static List<ChatMessage> jsonToHistory(JsonArray arr) {
		List<ChatMessage> out = new ArrayList<>(arr.size());
		for (int i = 0; i < arr.size(); i++) {
			out.add(jsonToMessage(arr.getJsonObject(i)));
		}
		return out;
	}

	/** JSON 对象 → 单条消息。 */
	private static ChatMessage jsonToMessage(JsonObject jo) {
		Role role = Role.fromValue(jo.getString("role"));
		String content = jo.optString("content", null);
		String toolCallId = jo.optString("toolCallId", null);
		List<ToolCall> toolCalls = null;
		if (jo.has("toolCalls")) {
			JsonArray arr = jo.getJsonArray("toolCalls");
			toolCalls = new ArrayList<>(arr.size());
			for (int i = 0; i < arr.size(); i++) {
				JsonObject co = arr.getJsonObject(i);
				String id = co.optString("id", null);
				String name = co.getString("name");
				String argsJson = co.optString("argumentsJson", null);
				toolCalls.add(new ToolCall(id, name, argsJson));
			}
		}
		return switch (role) {
			case SYSTEM -> ChatMessage.system(content);
			case USER -> ChatMessage.user(content);
			case ASSISTANT -> toolCalls != null
				? ChatMessage.assistant(toolCalls)
				: ChatMessage.assistant(content);
			case TOOL -> ChatMessage.tool(toolCallId, content);
		};
	}
}
