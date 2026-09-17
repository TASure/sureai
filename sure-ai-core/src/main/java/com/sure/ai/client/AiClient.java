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

package com.sure.ai.client;

import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * AI 对话客户端抽象。
 *
 * @author sureai
 * @since 0.1.0
 */
public interface AiClient {

	/**
	 * 客户端名（平台标识）。
	 *
	 * @return 名称
	 */
	String name();

	/**
	 * 同步对话。
	 *
	 * @param request 请求
	 * @return 响应
	 */
	ChatResponse chat(ChatRequest request);

	/**
	 * 流式对话，逐片投递。
	 *
	 * @param request  请求
	 * @param consumer 分片消费者
	 */
	void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer);

	/**
	 * 释放资源。
	 */
	void close();

	/**
	 * 便捷同步对话：构造只含一条 user 消息的请求。
	 *
	 * @param model  模型名
	 * @param prompt 用户输入
	 * @return 响应
	 */
	default ChatResponse chat(String model, String prompt) {
		ChatRequest req = ChatRequest.builder()
			.model(model)
			.messages(List.of(ChatMessage.user(prompt)))
			.build();
		return chat(req);
	}
}
