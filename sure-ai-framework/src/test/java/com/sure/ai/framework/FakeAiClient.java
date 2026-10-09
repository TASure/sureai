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

package com.sure.ai.framework;

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
 * 测试用对话客户端：捕获请求并按脚本返回文本/分片，零真实网络。
 *
 * @author sureai
 * @since 2.5.0
 */
final class FakeAiClient implements AiClient {

	private final List<ChatRequest> requests = new ArrayList<>();

	private final List<String> texts = new ArrayList<>();

	private final List<ChatStreamChunk> chunks = new ArrayList<>();

	private int idx;

	/**
	 * 追加一个阻塞文本响应。
	 *
	 * @param text 文本
	 * @return this
	 */
	FakeAiClient withText(String text) {
		this.texts.add(text);
		return this;
	}

	/**
	 * 追加流式分片。
	 *
	 * @param chunks 分片
	 * @return this
	 */
	FakeAiClient withChunks(ChatStreamChunk... chunks) {
		for (ChatStreamChunk c : chunks) {
			this.chunks.add(c);
		}
		return this;
	}

	/**
	 * 全部捕获的请求。
	 *
	 * @return 请求列表
	 */
	List<ChatRequest> requests() {
		return this.requests;
	}

	/**
	 * 最近一次请求。
	 *
	 * @return 最近请求
	 */
	ChatRequest last() {
		return this.requests.get(this.requests.size() - 1);
	}

	@Override
	public String name() {
		return "framework-fake";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		this.requests.add(request);
		String text;
		if (this.idx < this.texts.size()) {
			text = this.texts.get(this.idx);
		} else if (!this.texts.isEmpty()) {
			text = this.texts.get(this.texts.size() - 1);
		} else {
			text = "ok";
		}
		this.idx++;
		return resp(text);
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		this.requests.add(request);
		for (ChatStreamChunk c : this.chunks) {
			consumer.accept(c);
		}
	}

	@Override
	public void close() {
		// no-op
	}

	/** 构造一个仅含助手文本的响应。 */
	static ChatResponse resp(String text) {
		return ChatResponse.of("id", "test-model",
			List.of(Choice.of(0, ChatMessage.assistant(text), "stop")),
			TokenUsage.of(1, 1, 2), null);
	}

	/** 构造一个流式分片便捷方法。 */
	static ChatStreamChunk chunk(String text) {
		return ChatStreamChunk.of("id", Role.ASSISTANT, text, null, null);
	}
}
