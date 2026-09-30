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

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * 向量客户端异步装饰器（1.9.0）：包装任意 {@link EmbeddingClient}，在虚拟线程（或注入的执行器）
 * 上异步执行 embed，同步方法原样委托。
 *
 * <p>异步方法对齐 {@link EmbeddingClient} 的同步重载：{@link #embedAsync(EmbeddingRequest)}
 * 对应 {@link EmbeddingClient#embed(EmbeddingRequest)}，{@link #embedAsync(String, String)}
 * 对应 {@link EmbeddingClient#embed(String, String)}。异常原样进入未来；
 * {@code cancel(true)} 中断执行中的虚拟线程。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class AsyncEmbeddingClient implements EmbeddingClient {

	/** 被包装的底层客户端。 */
	private final EmbeddingClient delegate;

	/** 异步任务执行器。 */
	private final Executor executor;

	/**
	 * 用共享虚拟线程执行器包装。
	 *
	 * @param delegate 底层向量客户端
	 */
	public AsyncEmbeddingClient(EmbeddingClient delegate) {
		this(delegate, AsyncExecutors.virtualThreadExecutor());
	}

	/**
	 * 用自定义执行器包装。
	 *
	 * @param delegate 底层向量客户端
	 * @param executor 异步执行器（调用方负责其生命周期）
	 */
	public AsyncEmbeddingClient(EmbeddingClient delegate, Executor executor) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		return delegate.embed(request);
	}

	@Override
	public EmbeddingResponse embed(String model, String text) {
		return delegate.embed(model, text);
	}

	/**
	 * 异步向量请求。
	 *
	 * @param request 请求
	 * @return 异步响应未来
	 */
	public CompletableFuture<EmbeddingResponse> embedAsync(EmbeddingRequest request) {
		return AsyncExecutors.supplyAsync(() -> delegate.embed(request), executor);
	}

	/**
	 * 异步便捷向量：单条文本。
	 *
	 * @param model 模型名
	 * @param text  文本
	 * @return 异步响应未来
	 */
	public CompletableFuture<EmbeddingResponse> embedAsync(String model, String text) {
		return AsyncExecutors.supplyAsync(() -> delegate.embed(model, text), executor);
	}
}
