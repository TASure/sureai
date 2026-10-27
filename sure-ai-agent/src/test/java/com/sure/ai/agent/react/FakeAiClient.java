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
package com.sure.ai.agent.react;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;

/**
 * 测试用对话客户端：按调用次数依次返回预设响应，零真实网络。
 */
final class FakeAiClient implements AiClient {

	private final List<ChatResponse> responses = new ArrayList<>();
	private final List<ChatRequest> requests = new ArrayList<>();
	private int idx;

	/**
	 * 追加一个纯文本响应。
	 *
	 * @param text 文本
	 * @return this
	 */
	FakeAiClient withText(String text) {
		this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(text), "stop")),
				TokenUsage.of(1, 1, 2), null));
		return this;
	}

	/**
	 * 追加一个带工具调用的响应。
	 *
	 * @param calls 工具调用
	 * @return this
	 */
	FakeAiClient withToolCalls(List<ToolCall> calls) {
		this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(calls), "tool_calls")),
				TokenUsage.of(1, 1, 2), null));
		return this;
	}

	/**
	 * 最近一次请求。
	 *
	 * @return 最近请求
	 */
	ChatRequest lastRequest() {
		return this.requests.isEmpty() ? null : this.requests.get(this.requests.size() - 1);
	}

	/**
	 * 全部请求（按调用顺序）。
	 *
	 * @return 请求列表
	 */
	List<ChatRequest> requests() {
		return this.requests;
	}

	@Override
	public String name() {
		return "fake-ai";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		this.requests.add(request);
		if (this.idx >= this.responses.size()) {
			// 兜底：永远返回文本，避免死循环
			return this.responses.get(this.responses.size() - 1);
		}
		return this.responses.get(this.idx++);
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		// 测试不使用流式
	}

	@Override
	public void close() {
		// no-op
	}
}
