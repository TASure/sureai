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

package com.sure.ai.framework.advisor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;

/**
 * 测试用脚本化对话客户端：按脚本顺序返回预设响应，捕获全部请求，零真实网络。
 *
 * @author sureai
 * @since 2.5.0
 */
final class ScriptedClient implements AiClient {

	private final List<ChatRequest> requests = new ArrayList<>();

	private final Deque<ChatResponse> script = new ArrayDeque<>();

	private ChatResponse repeatLast;

	/**
	 * 追加一个脚本响应。
	 *
	 * @param resp 响应
	 * @return this
	 */
	ScriptedClient then(ChatResponse resp) {
		this.script.add(resp);
		return this;
	}

	/**
	 * 脚本耗尽后重复使用的兜底响应。
	 *
	 * @param resp 兜底响应
	 * @return this
	 */
	ScriptedClient repeat(ChatResponse resp) {
		this.repeatLast = resp;
		return this;
	}

	List<ChatRequest> requests() {
		return this.requests;
	}

	ChatRequest last() {
		return this.requests.get(this.requests.size() - 1);
	}

	int callCount() {
		return this.requests.size();
	}

	@Override
	public String name() {
		return "scripted";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		this.requests.add(request);
		ChatResponse r = this.script.pollFirst();
		return r != null ? r : this.repeatLast;
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		throw new UnsupportedOperationException("scripted client 不支持流式");
	}

	@Override
	public void close() {
	}

	/** 构造纯文本响应。 */
	static ChatResponse text(String content) {
		ChatMessage m = ChatMessage.assistant(content);
		return ChatResponse.of("id", "m", List.of(Choice.of(0, m, "stop")),
			TokenUsage.of(1, 1, 2), null);
	}

	/** 构造携带 tool_calls 的响应。 */
	static ChatResponse toolCalls(List<ToolCall> calls) {
		ChatMessage m = ChatMessage.of(Role.ASSISTANT, null, null, null, null, calls);
		return ChatResponse.of("id", "m", List.of(Choice.of(0, m, "tool_calls")),
			TokenUsage.of(1, 1, 2), null);
	}
}
