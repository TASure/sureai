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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.sure.ai.client.async.AsyncExecutors;
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

	/**
	 * 异步对话（1.9.0）：在共享虚拟线程执行器上执行 {@link #chat(ChatRequest)}。
	 *
	 * <p>默认实现把同步 {@link #chat(ChatRequest)} 提交到
	 * {@link AsyncExecutors#virtualThreadExecutor()}（Java 21 虚拟线程），因此既有全部平台
	 * Client<b>无需任何改动</b>即获得异步能力。底层抛出的异常（{@code AiException} 等）原样
	 * 进入未来异常完成态；{@code cancel(true)} 会中断执行中的虚拟线程。</p>
	 *
	 * <p>需要使用自定义执行器或受控生命周期时，请用
	 * {@link com.sure.ai.client.async.AsyncAiClient} 包装本客户端。</p>
	 *
	 * @param request 请求
	 * @return 异步响应未来
	 * @since 1.9.0
	 */
	default CompletableFuture<ChatResponse> chatAsync(ChatRequest request) {
		return AsyncExecutors.supplyAsync(() -> chat(request), AsyncExecutors.virtualThreadExecutor());
	}

	/**
	 * 异步流式对话（1.9.0）：在共享虚拟线程执行器上执行 {@link #chatStream}。
	 *
	 * <p>分片仍在工作虚拟线程上同步回调 {@code consumer}；返回的未来在<b>流正常结束</b>时以
	 * {@code null} 完成，在<b>流过程中抛出异常</b>时以原异常完成。消费方需自行保证
	 * {@code consumer} 的线程安全。</p>
	 *
	 * @param request  请求
	 * @param consumer 分片消费者
	 * @return 流结束（或异常）时完成的未来
	 * @since 1.9.0
	 */
	default CompletableFuture<Void> chatStreamAsync(ChatRequest request,
			Consumer<ChatStreamChunk> consumer) {
		return AsyncExecutors.runAsync(() -> chatStream(request, consumer),
			AsyncExecutors.virtualThreadExecutor());
	}
}
