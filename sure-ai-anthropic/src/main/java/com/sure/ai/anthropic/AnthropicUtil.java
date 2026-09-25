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

package com.sure.ai.anthropic;

import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * Anthropic 静态入口工具类。
 *
 * <p>双检锁懒加载单例；支持 {@link #init(String)} / {@link #init(AiConfig)} 显式初始化，
 * 或通过环境变量 {@code SURE_AI_ANTHROPIC_API_KEY}、{@code SURE_AI_ANTHROPIC_BASE_URL} 自动配置。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class AnthropicUtil {

	/** 默认 baseUrl。 */
	private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";

	/** 单例客户端容器（封装 DCL 懒加载）。 */
	private static final SingletonHolder<AnthropicClient> HOLDER =
		new SingletonHolder<>(AnthropicUtil::loadFromEnv);

	private AnthropicUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 API Key 初始化（使用默认 baseUrl）。
	 *
	 * @param apiKey Anthropic API Key
	 */
	public static void init(String apiKey) {
		init(AiConfig.builder().apiKey(apiKey).baseUrl(DEFAULT_BASE_URL).build());
	}

	/**
	 * 用完整配置初始化。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		HOLDER.set(new AnthropicClient(config));
	}

	/**
	 * 获取单例客户端，未初始化时从环境变量读取。
	 *
	 * @return 客户端
	 */
	public static AnthropicClient client() {
		return HOLDER.get();
	}

	/** 从环境变量懒加载构造客户端。 */
	private static AnthropicClient loadFromEnv() {
		String apiKey = System.getenv("SURE_AI_ANTHROPIC_API_KEY");
		String baseUrl = System.getenv("SURE_AI_ANTHROPIC_BASE_URL");
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = DEFAULT_BASE_URL;
		}
		return new AnthropicClient(AiConfig.builder().apiKey(apiKey).baseUrl(baseUrl).build());
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
	 * 客户端名。
	 *
	 * @return 名称
	 */
	public static String name() {
		return client().name();
	}
}
