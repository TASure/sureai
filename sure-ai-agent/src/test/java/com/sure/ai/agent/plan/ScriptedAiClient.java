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

package com.sure.ai.agent.plan;

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
 * 测试用对话客户端：按调用顺序返回脚本化响应或抛脚本化异常，零真实网络。
 *
 * <p>与 {@code com.sure.ai.agent.react.FakeAiClient} 功能对齐，额外支持
 * {@link #withError(RuntimeException)} 以模拟步骤执行失败重试场景。</p>
 */
final class ScriptedAiClient implements AiClient {

	private final List<ChatResponse> responses = new ArrayList<>();
	private final List<RuntimeException> errors = new ArrayList<>();
	private final List<ChatRequest> requests = new ArrayList<>();
	private int idx;

	/**
	 * 追加一个纯文本响应。
	 *
	 * @param text 文本
	 * @return this
	 */
	ScriptedAiClient withText(String text) {
		this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(text), "stop")),
				TokenUsage.of(1, 1, 2), null));
		this.errors.add(null);
		return this;
	}

	/**
	 * 追加一个带工具调用的响应。
	 *
	 * @param calls 工具调用
	 * @return this
	 */
	ScriptedAiClient withToolCalls(List<ToolCall> calls) {
		this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(calls), "tool_calls")),
				TokenUsage.of(1, 1, 2), null));
		this.errors.add(null);
		return this;
	}

	/**
	 * 追加一个会抛出异常的调用槽位。
	 *
	 * @param error 要抛出的异常
	 * @return this
	 */
	ScriptedAiClient withError(RuntimeException error) {
		this.responses.add(null);
		this.errors.add(error);
		return this;
	}

	/**
	 * 全部请求（按调用顺序）。
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
	ChatRequest lastRequest() {
		return this.requests.isEmpty() ? null : this.requests.get(this.requests.size() - 1);
	}

	@Override
	public String name() {
		return "scripted-fake";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		this.requests.add(request);
		if (this.idx >= this.responses.size()) {
			return this.responses.get(this.responses.size() - 1);
		}
		RuntimeException err = this.errors.get(this.idx);
		ChatResponse resp = this.responses.get(this.idx);
		this.idx++;
		if (err != null) {
			throw err;
		}
		return resp;
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
