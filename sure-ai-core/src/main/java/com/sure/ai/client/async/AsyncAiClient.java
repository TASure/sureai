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

package com.sure.ai.client.async;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * 对话客户端异步装饰器（1.9.0）：包装任意 {@link AiClient}，提供<b>可注入执行器</b>的全量异步
 * 方法族，同步方法与 {@link #close()} 原样委托底层客户端。
 *
 * <h2>与 {@code AiClient} 默认异步方法的关系</h2>
 * <p>{@link AiClient} 已带 {@code default chatAsync/chatStreamAsync}（走共享虚拟线程执行器），
 * 满足绝大多数“开箱即用”场景。本装饰器额外解决两点：</p>
 * <ul>
 *   <li><b>自定义执行器</b>：通过 {@link #AsyncAiClient(AiClient, Executor)} 注入受控线程池，
 *       便于限流、隔离与生命周期管理；</li>
 *   <li><b>类型明确</b>：把一个普通 {@code AiClient} 静态声明为异步门面，调用点不依赖默认方法。</li>
 * </ul>
 *
 * <p>装饰器线程安全：除 {@link #close()} 外所有方法均为无状态委托，底层客户端自身需线程安全
 * （平台客户端无状态、线程安全）。{@link #close()} 仅委托底层客户端，<b>不关闭</b>本类使用的执行器
 * （共享虚拟线程执行器随进程存活；注入的执行器由调用方自行管理）。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class AsyncAiClient implements AiClient {

	/** 被包装的底层客户端。 */
	private final AiClient delegate;

	/** 异步任务执行器（虚拟线程或自定义池）。 */
	private final Executor executor;

	/**
	 * 用共享虚拟线程执行器包装。
	 *
	 * @param delegate 底层对话客户端
	 */
	public AsyncAiClient(AiClient delegate) {
		this(delegate, AsyncExecutors.virtualThreadExecutor());
	}

	/**
	 * 用自定义执行器包装。
	 *
	 * @param delegate 底层对话客户端
	 * @param executor 异步执行器（调用方负责其生命周期）
	 */
	public AsyncAiClient(AiClient delegate, Executor executor) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
	}

	@Override
	public String name() {
		return delegate.name();
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		return delegate.chat(request);
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		delegate.chatStream(request, consumer);
	}

	@Override
	public void close() {
		delegate.close();
	}

	@Override
	public CompletableFuture<ChatResponse> chatAsync(ChatRequest request) {
		return AsyncExecutors.supplyAsync(() -> delegate.chat(request), executor);
	}

	@Override
	public CompletableFuture<Void> chatStreamAsync(ChatRequest request,
			Consumer<ChatStreamChunk> consumer) {
		return AsyncExecutors.runAsync(() -> delegate.chatStream(request, consumer), executor);
	}
}
