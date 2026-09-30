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

import com.sure.ai.client.ImageClient;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;

/**
 * 图像生成客户端异步装饰器（1.9.0）：包装任意 {@link ImageClient}，在虚拟线程（或注入执行器）上
 * 异步执行图像生成。图像平台内部可能已含“提交任务→轮询→取结果”全链路，该阻塞过程整体跑在虚拟线程上，
 * 不占用载体平台线程。
 *
 * <p>异步方法对齐同步重载：{@link #generateAsync(ImageRequest)} 对应
 * {@link ImageClient#generate(ImageRequest)}，{@link #generateAsync(String, String)} 对应
 * {@link ImageClient#generate(String, String)}。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class AsyncImageClient implements ImageClient {

	/** 被包装的底层客户端。 */
	private final ImageClient delegate;

	/** 异步任务执行器。 */
	private final Executor executor;

	/**
	 * 用共享虚拟线程执行器包装。
	 *
	 * @param delegate 底层图像客户端
	 */
	public AsyncImageClient(ImageClient delegate) {
		this(delegate, AsyncExecutors.virtualThreadExecutor());
	}

	/**
	 * 用自定义执行器包装。
	 *
	 * @param delegate 底层图像客户端
	 * @param executor 异步执行器（调用方负责其生命周期）
	 */
	public AsyncImageClient(ImageClient delegate, Executor executor) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
	}

	@Override
	public ImageResponse generate(ImageRequest request) {
		return delegate.generate(request);
	}

	@Override
	public ImageResponse generate(String model, String prompt) {
		return delegate.generate(model, prompt);
	}

	/**
	 * 异步图像生成。
	 *
	 * @param request 请求
	 * @return 异步响应未来
	 */
	public CompletableFuture<ImageResponse> generateAsync(ImageRequest request) {
		return AsyncExecutors.supplyAsync(() -> delegate.generate(request), executor);
	}

	/**
	 * 异步便捷图像生成。
	 *
	 * @param model  模型名
	 * @param prompt 提示词
	 * @return 异步响应未来
	 */
	public CompletableFuture<ImageResponse> generateAsync(String model, String prompt) {
		return AsyncExecutors.supplyAsync(() -> delegate.generate(model, prompt), executor);
	}
}
