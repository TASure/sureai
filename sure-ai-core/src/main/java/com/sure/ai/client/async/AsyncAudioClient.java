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

import com.sure.ai.client.AudioClient;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;

/**
 * 语音客户端异步装饰器（1.9.0）：包装任意 {@link AudioClient}，在虚拟线程（或注入执行器）上异步执行
 * TTS 合成与 STT 转录。异步方法对齐 {@link AudioClient} 的同步重载。
 *
 * @author sureai
 * @since 1.9.0
 */
public final class AsyncAudioClient implements AudioClient {

	/** 被包装的底层客户端。 */
	private final AudioClient delegate;

	/** 异步任务执行器。 */
	private final Executor executor;

	/**
	 * 用共享虚拟线程执行器包装。
	 *
	 * @param delegate 底层语音客户端
	 */
	public AsyncAudioClient(AudioClient delegate) {
		this(delegate, AsyncExecutors.virtualThreadExecutor());
	}

	/**
	 * 用自定义执行器包装。
	 *
	 * @param delegate 底层语音客户端
	 * @param executor 异步执行器（调用方负责其生命周期）
	 */
	public AsyncAudioClient(AudioClient delegate, Executor executor) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
	}

	@Override
	public TtsResponse synthesize(TtsRequest request) {
		return delegate.synthesize(request);
	}

	@Override
	public TtsResponse synthesize(String model, String text, String voice) {
		return delegate.synthesize(model, text, voice);
	}

	@Override
	public SttResponse transcribe(SttRequest request) {
		return delegate.transcribe(request);
	}

	@Override
	public SttResponse transcribe(String model, byte[] audioData) {
		return delegate.transcribe(model, audioData);
	}

	/**
	 * 异步语音合成（TTS）。
	 *
	 * @param request 请求
	 * @return 异步响应未来
	 */
	public CompletableFuture<TtsResponse> synthesizeAsync(TtsRequest request) {
		return AsyncExecutors.supplyAsync(() -> delegate.synthesize(request), executor);
	}

	/**
	 * 异步便捷语音合成。
	 *
	 * @param model 模型名
	 * @param text  待合成文本
	 * @param voice 音色 ID
	 * @return 异步响应未来
	 */
	public CompletableFuture<TtsResponse> synthesizeAsync(String model, String text, String voice) {
		return AsyncExecutors.supplyAsync(() -> delegate.synthesize(model, text, voice), executor);
	}

	/**
	 * 异步语音识别（STT/转录）。
	 *
	 * @param request 请求（含音频二进制数据）
	 * @return 异步响应未来
	 */
	public CompletableFuture<SttResponse> transcribeAsync(SttRequest request) {
		return AsyncExecutors.supplyAsync(() -> delegate.transcribe(request), executor);
	}

	/**
	 * 异步便捷语音识别。
	 *
	 * @param model     模型名
	 * @param audioData 音频二进制数据
	 * @return 异步响应未来
	 */
	public CompletableFuture<SttResponse> transcribeAsync(String model, byte[] audioData) {
		return AsyncExecutors.supplyAsync(() -> delegate.transcribe(model, audioData), executor);
	}
}
