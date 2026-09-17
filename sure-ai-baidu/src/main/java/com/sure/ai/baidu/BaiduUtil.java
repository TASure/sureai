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

package com.sure.ai.baidu;

import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * 百度千帆（文心 ERNIE）静态入口。
 *
 * <p>双检锁懒加载单例；首次使用前可通过 {@link #init(String, String)} / {@link #init(AiConfig)}
 * 注入配置，否则从环境变量读取：</p>
 * <ul>
 *   <li>{@code SURE_AI_BAIDU_API_KEY}：千帆 API Key（必填）</li>
 *   <li>{@code SURE_AI_BAIDU_SECRET_KEY}：千帆 Secret Key（必填）</li>
 *   <li>{@code SURE_AI_BAIDU_BASE_URL}：可选，缺省 {@code https://aip.baidubce.com}</li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class BaiduUtil {

	/** 环境变量：API Key。 */
	public static final String ENV_API_KEY = "SURE_AI_BAIDU_API_KEY";

	/** 环境变量：Secret Key。 */
	public static final String ENV_SECRET_KEY = "SURE_AI_BAIDU_SECRET_KEY";

	/** 环境变量：baseUrl。 */
	public static final String ENV_BASE_URL = "SURE_AI_BAIDU_BASE_URL";

	/** 全局单例。 */
	private static volatile BaiduClient client;

	/** 初始化锁。 */
	private static final Object LOCK = new Object();

	/** 工具类禁止实例化。 */
	private BaiduUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 API Key 与 Secret Key 初始化。
	 *
	 * @param apiKey    千帆 API Key
	 * @param secretKey 千帆 Secret Key
	 */
	public static void init(String apiKey, String secretKey) {
		synchronized (LOCK) {
			AiConfig cfg = AiConfig.builder()
				.apiKey(apiKey)
				.extraHeader(BaiduClient.SECRET_KEY_HEADER, secretKey)
				.build();
			client = new BaiduClient(cfg);
		}
	}

	/**
	 * 用完整配置初始化（secretKey 需通过 extraHeader("secretKey", ...) 传入）。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		synchronized (LOCK) {
			client = new BaiduClient(config);
		}
	}

	/**
	 * 获取单例；未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static BaiduClient client() {
		BaiduClient c = client;
		if (c == null) {
			synchronized (LOCK) {
				c = client;
				if (c == null) {
					c = buildFromEnv();
					client = c;
				}
			}
		}
		return c;
	}

	/** 从环境变量构建客户端。 */
	private static BaiduClient buildFromEnv() {
		String key = System.getenv(ENV_API_KEY);
		String secret = System.getenv(ENV_SECRET_KEY);
		if (key == null || key.isBlank()) {
			throw new AiException("env " + ENV_API_KEY + " is not set");
		}
		if (secret == null || secret.isBlank()) {
			throw new AiException("env " + ENV_SECRET_KEY + " is not set");
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(key)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, secret);
		String base = System.getenv(ENV_BASE_URL);
		if (base != null && !base.isBlank()) {
			b.baseUrl(base);
		}
		return new BaiduClient(b.build());
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param model  模型 ID（URL 路径参数）
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
	 * 文本向量。
	 *
	 * @param request 向量请求
	 * @return 向量响应
	 */
	public static EmbeddingResponse embed(EmbeddingRequest request) {
		return client().embed(request);
	}
}
