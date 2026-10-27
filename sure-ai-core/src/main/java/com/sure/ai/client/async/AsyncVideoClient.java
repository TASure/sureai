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

import com.sure.ai.client.VideoClient;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * 视频生成客户端异步装饰器（1.9.0）：包装任意 {@link VideoClient}，在虚拟线程（或注入执行器）上
 * 异步执行视频生成。视频平台内部为“提交任务→轮询状态→取结果”的长阻塞过程，整体跑在一条虚拟线程上，
 * 轮询期间不占用载体平台线程。
 *
 * @author sureai
 * @since 1.9.0
 */
public final class AsyncVideoClient implements VideoClient {

	/** 被包装的底层客户端。 */
	private final VideoClient delegate;

	/** 异步任务执行器。 */
	private final Executor executor;

	/**
	 * 用共享虚拟线程执行器包装。
	 *
	 * @param delegate 底层视频客户端
	 */
	public AsyncVideoClient(VideoClient delegate) {
		this(delegate, AsyncExecutors.virtualThreadExecutor());
	}

	/**
	 * 用自定义执行器包装。
	 *
	 * @param delegate 底层视频客户端
	 * @param executor 异步执行器（调用方负责其生命周期）
	 */
	public AsyncVideoClient(VideoClient delegate, Executor executor) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
	}

	@Override
	public VideoResponse generate(VideoRequest request) {
		return delegate.generate(request);
	}

	/**
	 * 异步视频生成（内部完成异步任务轮询）。
	 *
	 * @param request 请求
	 * @return 异步响应未来
	 */
	public CompletableFuture<VideoResponse> generateAsync(VideoRequest request) {
		return AsyncExecutors.supplyAsync(() -> delegate.generate(request), executor);
	}
}
