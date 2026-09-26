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
package com.sure.ai.proxy;

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
 * 测试用 {@link AiClient}：零网络，记录最近一次请求的 model/tenantId，并返回固定响应。
 *
 * @author sureai
 * @since 1.6.0
 */
final class RecordingChatClient implements AiClient {

	/** 固定返回文本。 */
	private static final String FIXED_TEXT = "hello back";

	/** 最近一次请求的 model。 */
	private volatile String lastModel;

	/** 最近一次请求解析出的 tenantId。 */
	private volatile String lastTenantId;

	/** 最近一次是否为流式。 */
	private volatile boolean lastStream;

	/** chat 调用次数。 */
	private volatile int chatCalls;

	/** chat/chatStream 抛出的异常（null 表示成功）。 */
	private RuntimeException throwOnCall;

	/**
	 * 配置调用时抛出异常。
	 *
	 * @param e 异常
	 * @return this
	 */
	RecordingChatClient throwOn(RuntimeException e) {
		this.throwOnCall = e;
		return this;
	}

	@Override
	public String name() {
		return "fake-chat";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		record(request);
		this.chatCalls++;
		if (this.throwOnCall != null) {
			throw this.throwOnCall;
		}
		return fixedResponse(request.model());
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		record(request);
		if (this.throwOnCall != null) {
			throw this.throwOnCall;
		}
		consumer.accept(ChatStreamChunk.of("chunk-1", Role.ASSISTANT, "Hello", null, null));
		consumer.accept(ChatStreamChunk.of("chunk-1", null, " world", null, "stop"));
	}

	@Override
	public void close() {
	}

	private void record(ChatRequest request) {
		this.lastModel = request.model();
		this.lastStream = request.stream();
		Object tenant = request.extra().get("tenantId");
		this.lastTenantId = tenant instanceof String s ? s : null;
	}

	private static ChatResponse fixedResponse(String model) {
		Choice choice = Choice.of(0, ChatMessage.assistant(FIXED_TEXT), "stop");
		return ChatResponse.of("resp-1", model, List.of(choice),
				TokenUsage.of(11, 22, 33), "{}");
	}

	/** 最近一次 model。 */
	String lastModel() {
		return this.lastModel;
	}

	/** 最近一次 tenantId。 */
	String lastTenantId() {
		return this.lastTenantId;
	}

	/** 最近一次是否流式。 */
	boolean lastStream() {
		return this.lastStream;
	}

	/** chat 调用次数。 */
	int chatCalls() {
		return this.chatCalls;
	}
}
