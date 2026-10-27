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

package com.sure.ai.rag;

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
 * 测试用对话客户端：记录最近一次请求，返回固定响应。
 */
public final class TestChatClient implements AiClient {

	private ChatRequest lastRequest;
	private final String reply;

	public TestChatClient(String reply) {
		this.reply = reply;
	}

	/**
	 * 返回最近一次请求。
	 *
	 * @return 最近一次请求
	 */
	public ChatRequest lastRequest() {
		return lastRequest;
	}

	@Override
	public String name() {
		return "test-chat";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		this.lastRequest = request;
		return ChatResponse.of("resp-1", request.model(),
				List.of(Choice.of(0, ChatMessage.assistant(reply), "stop")),
				TokenUsage.of(1, 1, 2), null);
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		// 测试无需流式
	}

	@Override
	public void close() {
		// no-op
	}
}
