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
import java.util.function.Consumer;

import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Role;
import com.sure.ai.model.ToolCall;

/**
 * 流式 Chat 能力域策略：{@code /chat/completions} SSE 分片解析。
 *
 * <p>SSE 行协议与 {@code [DONE]} 终止由 {@code AbstractAiClient#doPostStream} 统一处理，
 * 本类仅负责把每个 {@code data: {...}} JSON 元素映射为 {@link ChatStreamChunk}
 * （delta 角色/文本/工具调用累加、finish_reason 透传）。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
final class StreamCompatStrategy {

	/** 持有外层客户端引用，用于 buildChatBody 虚方法分发与传输。 */
	private final OpenAiCompatClient client;

	/**
	 * @param client 外层 OpenAI 兼容客户端
	 */
	StreamCompatStrategy(OpenAiCompatClient client) {
		this.client = client;
	}

	/** 流式 chat 入口：逐分片投递。 */
	void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		JsonObject body = this.client.buildChatBody(request, true);
		this.client.transportPostStream(this.client.chatPath, body,
			el -> consumer.accept(parseChunk(el.getAsJsonObject())));
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
}
