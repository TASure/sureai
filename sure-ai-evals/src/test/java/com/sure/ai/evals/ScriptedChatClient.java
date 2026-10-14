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
package com.sure.ai.evals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;

/**
 * 测试用脚本化对话客户端：按队列返回预设回复，零网络，记录每次提示词。
 */
class ScriptedChatClient implements AiClient {

	private final List<String> replies;
	private final List<String> prompts = new ArrayList<>();
	private int cursor;
	private RuntimeException error;
	private TokenUsage usage = TokenUsage.of(2, 3, 5);

	ScriptedChatClient(String... replies) {
		this.replies = new ArrayList<>(Arrays.asList(replies));
	}

	List<String> prompts() {
		return this.prompts;
	}

	ScriptedChatClient alwaysThrow(RuntimeException e) {
		this.error = e;
		return this;
	}

	ScriptedChatClient withUsage(TokenUsage usage) {
		this.usage = usage;
		return this;
	}

	@Override
	public String name() {
		return "scripted";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		String prompt = request.messages().get(0).content();
		this.prompts.add(prompt);
		if (this.error != null) {
			throw this.error;
		}
		if (this.cursor >= this.replies.size()) {
			throw new IllegalStateException("脚本回复耗尽，第 " + (this.cursor + 1) + " 次调用");
		}
		String reply = this.replies.get(this.cursor++);
		return ChatResponse.of("resp-" + this.cursor, request.model(),
			List.of(Choice.of(0, ChatMessage.assistant(reply), "stop")),
			this.usage, null);
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
