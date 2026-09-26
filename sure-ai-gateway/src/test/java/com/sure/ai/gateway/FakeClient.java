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
package com.sure.ai.gateway;

import java.util.ArrayList;
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

/**
 * 测试用 Fake {@link AiClient}：零网络，可配置抛错与流式分片。
 *
 * @author sureai
 * @since 1.6.0
 */
final class FakeClient implements AiClient {

	/** 客户端名。 */
	final String name;

	/** chat() 调用计数。 */
	int chatCalls;

	/** 是否被 close()。 */
	boolean closed;

	/** chatStream 投递的分片文本。 */
	private final List<String> streamDeltas = new ArrayList<>();

	/** chat/chatStream 抛出的异常（null 表示成功）。 */
	private RuntimeException throwOnCall;

	FakeClient(String name) {
		this.name = name;
	}

	/**
	 * 构造一个含单条 assistant 文本的响应。
	 *
	 * @param text 文本
	 * @return 响应
	 */
	static ChatResponse response(String text) {
		Choice choice = Choice.of(0, ChatMessage.assistant(text), "stop");
		return ChatResponse.of("resp-" + text, "model-x", List.of(choice),
				TokenUsage.of(1, 1, 2), "{}");
	}

	/**
	 * 配置调用时抛出的异常。
	 *
	 * @param e 异常
	 * @return this
	 */
	FakeClient throwOn(RuntimeException e) {
		this.throwOnCall = e;
		return this;
	}

	/**
	 * 配置流式分片。
	 *
	 * @param deltas 分片文本
	 * @return this
	 */
	FakeClient stream(String... deltas) {
		for (String d : deltas) {
			this.streamDeltas.add(d);
		}
		return this;
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		this.chatCalls++;
		if (this.throwOnCall != null) {
			throw this.throwOnCall;
		}
		return response(this.name + ":ok");
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		if (this.throwOnCall != null) {
			throw this.throwOnCall;
		}
		int i = 0;
		int total = this.streamDeltas.size();
		for (String d : this.streamDeltas) {
			String finish = (i == total - 1) ? "stop" : null;
			consumer.accept(ChatStreamChunk.of("chunk", Role.ASSISTANT, d, null, finish));
			i++;
		}
	}

	@Override
	public void close() {
		this.closed = true;
	}
}
