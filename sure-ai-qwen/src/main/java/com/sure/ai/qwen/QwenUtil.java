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

package com.sure.ai.qwen;

import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;

/**
 * 阿里云百炼通义千问（DashScope）平台静态入口工具类。
 *
 * <p>双检锁懒加载单例：调用 {@link #init(String)} 或 {@link #init(AiConfig)} 显式初始化；
 * 未初始化时从环境变量读取：</p>
 * <ul>
 *   <li>{@code SURE_AI_QWEN_API_KEY}（必填，缺失抛 {@link AiException}）</li>
 *   <li>{@code SURE_AI_QWEN_BASE_URL}（可选，覆盖默认 baseUrl）</li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class QwenUtil {

	/** 环境变量名：API Key。 */
	public static final String ENV_API_KEY = "SURE_AI_QWEN_API_KEY";

	/** 环境变量名：baseUrl 覆盖。 */
	public static final String ENV_BASE_URL = "SURE_AI_QWEN_BASE_URL";

	private static volatile QwenClient client;

	/** 初始化锁对象。 */
	private static final Object LOCK = new Object();

	/** 图像生成客户端单例。 */
	private static volatile QwenImageClient imageClient;

	/** 图像客户端初始化锁。 */
	private static final Object IMAGE_LOCK = new Object();

	private QwenUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 API Key 显式初始化。
	 *
	 * @param apiKey API Key
	 */
	public static void init(String apiKey) {
		synchronized (LOCK) {
			client = new QwenClient(AiConfig.of(apiKey));
		}
	}

	/**
	 * 用完整配置显式初始化。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		synchronized (LOCK) {
			client = new QwenClient(config);
		}
	}

	/**
	 * 获取单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 未初始化且未设置 {@code SURE_AI_QWEN_API_KEY}
	 */
	public static QwenClient client() {
		QwenClient c = client;
		if (c == null) {
			synchronized (LOCK) {
				c = client;
				if (c == null) {
					c = new QwenClient(buildConfigFromEnv());
					client = c;
				}
			}
		}
		return c;
	}

	/** 从环境变量构造配置。 */
	static AiConfig buildConfigFromEnv() {
		String apiKey = System.getenv(ENV_API_KEY);
		if (apiKey == null || apiKey.isBlank()) {
			throw new AiException("未设置环境变量 " + ENV_API_KEY
				+ "，请先调用 QwenUtil.init(apiKey) 或配置该环境变量");
		}
		return buildConfig(apiKey, System.getenv(ENV_BASE_URL));
	}

	/**
	 * 用 API Key 与可选 baseUrl 构造配置。
	 *
	 * @param apiKey  API Key
	 * @param baseUrl baseUrl，可空
	 * @return 配置
	 */
	static AiConfig buildConfig(String apiKey, String baseUrl) {
		AiConfig.Builder b = AiConfig.builder().apiKey(apiKey);
		if (baseUrl != null && !baseUrl.isBlank()) {
			b.baseUrl(baseUrl);
		}
		return b.build();
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param model  模型 ID
	 * @param prompt 用户输入
	 * @return 响应
	 */
	public static ChatResponse chat(String model, String prompt) {
		return client().chat(model, prompt);
	}

	/**
	 * 同步对话。
	 *
	 * @param request 请求
	 * @return 响应
	 */
	public static ChatResponse chat(ChatRequest request) {
		return client().chat(request);
	}

	/**
	 * 流式对话。
	 *
	 * @param request  请求
	 * @param consumer 分片消费者
	 */
	public static void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		client().chatStream(request, consumer);
	}

	/**
	 * 便捷向量：单条文本。
	 *
	 * @param model 模型 ID
	 * @param text  文本
	 * @return 向量响应
	 */
	public static EmbeddingResponse embed(String model, String text) {
		return client().embed(model, text);
	}

	/**
	 * 向量。
	 *
	 * @param request 向量请求
	 * @return 向量响应
	 */
	public static EmbeddingResponse embed(EmbeddingRequest request) {
		return client().embed(request);
	}

	/**
	 * 获取通义万相图像生成单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 图像客户端
	 * @throws AiException 未初始化且未设置 {@code SURE_AI_QWEN_API_KEY}
	 */
	public static QwenImageClient imageClient() {
		QwenImageClient c = imageClient;
		if (c == null) {
			synchronized (IMAGE_LOCK) {
				c = imageClient;
				if (c == null) {
					c = new QwenImageClient(buildConfigFromEnv());
					imageClient = c;
				}
			}
		}
		return c;
	}

	/**
	 * 便捷文生图。
	 *
	 * @param model  模型 ID（如 {@link QwenModels#WANX_V1}）
	 * @param prompt 提示词
	 * @return 图像响应
	 */
	public static ImageResponse image(String model, String prompt) {
		return imageClient().generate(model, prompt);
	}

	/**
	 * 文生图。
	 *
	 * @param request 图像请求
	 * @return 图像响应
	 */
	public static ImageResponse image(ImageRequest request) {
		return imageClient().generate(request);
	}

	/**
	 * 重置图像单例客户端（测试清理用）。
	 */
	public static void resetImageClient() {
		synchronized (IMAGE_LOCK) {
			imageClient = null;
		}
	}
}
