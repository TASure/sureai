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

package com.sure.ai.gemini;

import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;

/**
 * Google Gemini 静态入口工具类。
 *
 * <p>双检锁懒加载单例；支持 {@link #init(String)} / {@link #init(AiConfig)} 显式初始化，
 * 或通过环境变量 {@code SURE_AI_GEMINI_API_KEY}、{@code SURE_AI_GEMINI_BASE_URL} 自动配置。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class GeminiUtil {

	/** 默认 baseUrl。 */
	private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

	/** 主客户端容器（封装 DCL 懒加载）。 */
	private static final SingletonHolder<GeminiClient> HOLDER =
		new SingletonHolder<>(GeminiUtil::loadFromEnv);

	/** Realtime 客户端容器（构造参数依赖调用参数，用 getOrCreate 懒加载）。 */
	private static final SingletonHolder<GeminiRealtimeClient> REALTIME =
		new SingletonHolder<>(null);

	private GeminiUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 API Key 初始化（使用默认 baseUrl）。
	 *
	 * @param apiKey Gemini API Key
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
		HOLDER.set(new GeminiClient(config));
	}

	/**
	 * 获取单例客户端，未初始化时从环境变量读取。
	 *
	 * @return 客户端
	 */
	public static GeminiClient client() {
		return HOLDER.get();
	}

	/** 从环境变量懒加载构造客户端。 */
	private static GeminiClient loadFromEnv() {
		String apiKey = System.getenv("SURE_AI_GEMINI_API_KEY");
		String baseUrl = System.getenv("SURE_AI_GEMINI_BASE_URL");
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = DEFAULT_BASE_URL;
		}
		return new GeminiClient(AiConfig.builder().apiKey(apiKey).baseUrl(baseUrl).build());
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
	 * 便捷图像生成：仅模型与提示词。
	 *
	 * @param model  模型名（如 {@link GeminiModels#GEMINI_2_0_FLASH_EXP}）
	 * @param prompt 提示词
	 * @return 图像响应
	 */
	public static ImageResponse image(String model, String prompt) {
		return client().generate(model, prompt);
	}

	/**
	 * 图像生成。
	 *
	 * @param request 图像请求
	 * @return 图像响应
	 */
	public static ImageResponse image(ImageRequest request) {
		return client().generate(request);
	}

	// ==================== Realtime（Gemini Live 全双工语音对话） ====================

	/**
	 * 获取 Realtime 单例客户端，未初始化时从环境变量懒加载。
	 *
	 * <p>Realtime 需要事件监听器，不提供静态便捷方法；单例首次以
	 * {@code (model, listener)} 构造，之后重复调用返回同一实例。</p>
	 *
	 * @param model        实时模型 ID（不含 models/ 前缀）
	 * @param eventListener 事件监听器
	 * @return Realtime 客户端
	 */
	public static GeminiRealtimeClient realtimeClient(String model,
			RealtimeEventListener eventListener) {
		return REALTIME.getOrCreate(() -> {
			String apiKey = System.getenv("SURE_AI_GEMINI_API_KEY");
			return new GeminiRealtimeClient(AiConfig.of(apiKey), model, eventListener);
		});
	}

	/**
	 * 重置 Realtime 单例客户端（测试清理用）。
	 */
	public static void resetRealtimeClient() {
		REALTIME.reset();
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
