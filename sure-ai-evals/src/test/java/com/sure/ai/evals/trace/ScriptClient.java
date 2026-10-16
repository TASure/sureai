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
 * trace 测试公共脚本化客户端：按队列回复、记录调用 / 关闭次数，零网络。
 */
class ScriptClient implements AiClient {

	final List<String> replies;
	final List<String> prompts = new ArrayList<>();
	int calls;
	int closes;
	RuntimeException error;

	ScriptClient(String... replies) {
		this.replies = new ArrayList<>(List.of(replies));
	}

	ScriptClient alwaysThrow(RuntimeException e) {
		this.error = e;
		return this;
	}

	@Override
	public String name() {
		return "script";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		this.calls++;
		String prompt = request.messages().get(0).content();
		this.prompts.add(prompt);
		if (this.error != null) {
			throw this.error;
		}
		if (this.calls > this.replies.size()) {
			throw new IllegalStateException("脚本耗尽");
		}
		return ChatResponse.of("resp-" + this.calls, request.model(),
			List.of(Choice.of(0, ChatMessage.assistant(this.replies.get(this.calls - 1)), "stop")),
			TokenUsage.of(2, 3, 5), null);
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		this.calls++;
		consumer.accept(ChatStreamChunk.of("s-1", null, "你好", null, null));
		consumer.accept(ChatStreamChunk.of("s-1", null, "世界",
			List.of(ToolCall.of("c1", "weather", "{}")), "stop"));
	}

	@Override
	public void close() {
		this.closes++;
	}
}
