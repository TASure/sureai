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

package com.sure.ai.cohere;

import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.RerankRequest;
import com.sure.ai.model.RerankResponse;

/**
 * Cohere v2 静态入口。
 *
 * <p>双检锁懒加载单例；首次使用前可通过 {@link #init(String)} / {@link #init(AiConfig)}
 * 注入配置，否则从环境变量读取：</p>
 * <ul>
 *   <li>{@code SURE_AI_COHERE_API_KEY}：Cohere API Key（必填）</li>
 *   <li>{@code SURE_AI_COHERE_BASE_URL}：可选，缺省 {@code https://api.cohere.com/v2}</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class CohereUtil {

	/** 环境变量：API Key。 */
	public static final String ENV_API_KEY = "SURE_AI_COHERE_API_KEY";

	/** 环境变量：baseUrl。 */
	public static final String ENV_BASE_URL = "SURE_AI_COHERE_BASE_URL";

	/** 主客户端容器（封装 DCL 懒加载）。 */
	private static final SingletonHolder<CohereClient> MAIN =
		new SingletonHolder<>(CohereUtil::buildFromEnv);

	/** 重排客户端容器。 */
	private static final SingletonHolder<CohereRerankClient> RERANK =
		new SingletonHolder<>(() -> new CohereRerankClient(envConfig()));

	/** 工具类禁止实例化。 */
	private CohereUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 API Key 初始化。
	 *
	 * @param apiKey Cohere API Key
	 */
	public static void init(String apiKey) {
		MAIN.set(new CohereClient(AiConfig.builder().apiKey(apiKey).build()));
	}

	/**
	 * 用完整配置初始化。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		MAIN.set(new CohereClient(config));
	}

	/**
	 * 获取单例；未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static CohereClient client() {
		return MAIN.get();
	}

	/** 从环境变量构建客户端。 */
	private static CohereClient buildFromEnv() {
		return new CohereClient(envConfig());
	}

	/** 从环境变量构建配置：{@code SURE_AI_COHERE_API_KEY} 必填，{@code SURE_AI_COHERE_BASE_URL} 可选。 */
	static AiConfig envConfig() {
		String key = System.getenv(ENV_API_KEY);
		if (key == null || key.isBlank()) {
			throw new AiException("env " + ENV_API_KEY + " is not set");
		}
		AiConfig.Builder b = AiConfig.builder().apiKey(key);
		String base = System.getenv(ENV_BASE_URL);
		if (base != null && !base.isBlank()) {
			b.baseUrl(base);
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
	 * 文本向量。
	 *
	 * @param request 向量请求
	 * @return 向量响应
	 */
	public static EmbeddingResponse embed(EmbeddingRequest request) {
		return client().embed(request);
	}

	// ==================== 重排（Rerank） ====================

	/**
	 * 获取重排单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 重排客户端
	 * @throws AiException 未初始化且未设置 {@code SURE_AI_COHERE_API_KEY}
	 */
	public static CohereRerankClient rerankClient() {
		return RERANK.get();
	}

	/**
	 * 便捷重排：查询 + 候选文档，默认模型 {@link CohereModels#RERANK_V3_5}。
	 *
	 * @param query      查询文本
	 * @param documents  候选文档列表
	 * @return 重排响应
	 */
	public static RerankResponse rerank(String query, List<String> documents) {
		return rerank(RerankRequest.builder()
			.model(CohereModels.RERANK_V3_5)
			.query(query)
			.documents(documents)
			.build());
	}

	/**
	 * 重排。
	 *
	 * @param request 重排请求
	 * @return 重排响应
	 */
	public static RerankResponse rerank(RerankRequest request) {
		return rerankClient().rerank(request);
	}

	/**
	 * 重置重排单例客户端（测试清理用）。
	 */
	public static void resetRerankClient() {
		RERANK.reset();
	}
}
