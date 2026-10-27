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

import java.util.concurrent.Executor;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.AudioClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.ImageClient;
import com.sure.ai.client.VideoClient;

/**
 * 异步客户端统一包装工厂（1.9.0）：一行代码把任意同步客户端包装为对应异步装饰器。
 *
 * <p>所有无参 {@code wrap} 方法默认使用 {@link AsyncExecutors#virtualThreadExecutor()} 共享虚拟线程
 * 执行器；带 {@link Executor} 重载用于注入受控线程池。装饰器本身也可直接 {@code new}。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class AsyncClients {

	/** 工具类禁止实例化。 */
	private AsyncClients() {
	}

	/**
	 * 包装对话客户端（共享虚拟线程执行器）。
	 *
	 * @param client 底层客户端
	 * @return 异步装饰器
	 */
	public static AsyncAiClient chat(AiClient client) {
		return new AsyncAiClient(client);
	}

	/**
	 * 包装对话客户端（自定义执行器）。
	 *
	 * @param client   底层客户端
	 * @param executor 异步执行器
	 * @return 异步装饰器
	 */
	public static AsyncAiClient chat(AiClient client, Executor executor) {
		return new AsyncAiClient(client, executor);
	}

	/**
	 * 包装向量客户端（共享虚拟线程执行器）。
	 *
	 * @param client 底层客户端
	 * @return 异步装饰器
	 */
	public static AsyncEmbeddingClient embed(EmbeddingClient client) {
		return new AsyncEmbeddingClient(client);
	}

	/**
	 * 包装向量客户端（自定义执行器）。
	 *
	 * @param client   底层客户端
	 * @param executor 异步执行器
	 * @return 异步装饰器
	 */
	public static AsyncEmbeddingClient embed(EmbeddingClient client, Executor executor) {
		return new AsyncEmbeddingClient(client, executor);
	}

	/**
	 * 包装图像客户端（共享虚拟线程执行器）。
	 *
	 * @param client 底层客户端
	 * @return 异步装饰器
	 */
	public static AsyncImageClient image(ImageClient client) {
		return new AsyncImageClient(client);
	}

	/**
	 * 包装图像客户端（自定义执行器）。
	 *
	 * @param client   底层客户端
	 * @param executor 异步执行器
	 * @return 异步装饰器
	 */
	public static AsyncImageClient image(ImageClient client, Executor executor) {
		return new AsyncImageClient(client, executor);
	}

	/**
	 * 包装视频客户端（共享虚拟线程执行器）。
	 *
	 * @param client 底层客户端
	 * @return 异步装饰器
	 */
	public static AsyncVideoClient video(VideoClient client) {
		return new AsyncVideoClient(client);
	}

	/**
	 * 包装视频客户端（自定义执行器）。
	 *
	 * @param client   底层客户端
	 * @param executor 异步执行器
	 * @return 异步装饰器
	 */
	public static AsyncVideoClient video(VideoClient client, Executor executor) {
		return new AsyncVideoClient(client, executor);
	}

	/**
	 * 包装语音客户端（共享虚拟线程执行器）。
	 *
	 * @param client 底层客户端
	 * @return 异步装饰器
	 */
	public static AsyncAudioClient audio(AudioClient client) {
		return new AsyncAudioClient(client);
	}

	/**
	 * 包装语音客户端（自定义执行器）。
	 *
	 * @param client   底层客户端
	 * @param executor 异步执行器
	 * @return 异步装饰器
	 */
	public static AsyncAudioClient audio(AudioClient client, Executor executor) {
		return new AsyncAudioClient(client, executor);
	}
}
