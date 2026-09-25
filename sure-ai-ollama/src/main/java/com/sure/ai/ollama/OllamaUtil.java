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

package com.sure.ai.ollama;

import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * Ollama 静态入口工具类。
 *
 * <p>双检锁懒加载单例；Ollama 为本地服务无需 API Key，可通过环境变量
 * {@code SURE_AI_OLLAMA_BASE_URL} 覆盖默认地址 {@code http://localhost:11434}。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class OllamaUtil {

	/** 默认 baseUrl。 */
	private static final String DEFAULT_BASE_URL = "http://localhost:11434";

	/** 单例客户端容器（封装 DCL 懒加载）。 */
	private static final SingletonHolder<OllamaClient> HOLDER =
		new SingletonHolder<>(OllamaUtil::loadFromEnv);

	private OllamaUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用默认配置初始化（localhost:11434，无 Key）。
	 */
	public static void init() {
		init(AiConfig.builder().apiKey("ollama-local").baseUrl(DEFAULT_BASE_URL).build());
	}

	/**
	 * 用完整配置初始化。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		HOLDER.set(new OllamaClient(config));
	}

	/**
	 * 获取单例客户端，未初始化时从环境变量读取。
	 *
	 * @return 客户端
	 */
	public static OllamaClient client() {
		return HOLDER.get();
	}

	/** 从环境变量懒加载构造客户端。 */
	private static OllamaClient loadFromEnv() {
		String baseUrl = System.getenv("SURE_AI_OLLAMA_BASE_URL");
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = DEFAULT_BASE_URL;
		}
		return new OllamaClient(AiConfig.builder().apiKey("ollama-local").baseUrl(baseUrl).build());
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param model  模型名
	 * @param prompt 用户输入
	 * @return 响应
	 */
	public static ChatResponse chat(String model, String prompt) {
		return client().chat(model, prompt);
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param request 请求
	 * @return 响应
	 */
	public static ChatResponse chat(ChatRequest request) {
		return client().chat(request);
	}

	/**
	 * 便捷流式对话。
	 *
	 * @param request  请求
	 * @param consumer 分片消费者
	 */
	public static void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		client().chatStream(request, consumer);
	}

	/**
	 * 便捷向量。
	 *
	 * @param model 模型名
	 * @param text  文本
	 * @return 响应
	 */
	public static EmbeddingResponse embed(String model, String text) {
		return client().embed(model, text);
	}

	/**
	 * 便捷向量。
	 *
	 * @param request 请求
	 * @return 响应
	 */
	public static EmbeddingResponse embed(EmbeddingRequest request) {
		return client().embed(request);
	}

	/**
	 * 客户端名。
	 *
	 * @return 名称
	 */
	public static String name() {
		return client().name();
	}
}
