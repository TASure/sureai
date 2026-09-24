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

package com.sure.ai.llamacpp;

import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.Model;

/**
 * llama.cpp server 静态入口工具类。
 *
 * <p>双检锁懒加载单例。默认连接 {@code http://localhost:8080/v1}，无鉴权。
 * 可通过环境变量覆盖：</p>
 * <ul>
 *   <li>{@code SURE_AI_LLAMACPP_BASE_URL} — 覆盖默认 baseUrl</li>
 *   <li>{@code SURE_AI_LLAMACPP_API_KEY} — 设置 Bearer Token（默认 "dummy"，server 端忽略）</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class LlamaCppUtil {

	/** 默认 baseUrl。 */
	private static final String DEFAULT_BASE_URL = "http://localhost:8080/v1";

	/** 单例客户端。 */
	private static volatile LlamaCppClient client;

	/** 初始化锁对象。 */
	private static final Object LOCK = new Object();

	private LlamaCppUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用默认配置初始化（localhost:8080/v1，无 Key）。
	 */
	public static void init() {
		init(AiConfig.builder().apiKey(LlamaCppClient.DEFAULT_API_KEY).baseUrl(DEFAULT_BASE_URL).build());
	}

	/**
	 * 用完整配置初始化。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		synchronized (LOCK) {
			client = new LlamaCppClient(config);
		}
	}

	/**
	 * 获取单例客户端，未初始化时从环境变量读取。
	 *
	 * @return 客户端
	 */
	public static LlamaCppClient client() {
		LlamaCppClient c = client;
		if (c == null) {
			synchronized (LOCK) {
				c = client;
				if (c == null) {
					String baseUrl = System.getenv("SURE_AI_LLAMACPP_BASE_URL");
					if (baseUrl == null || baseUrl.isBlank()) {
						baseUrl = DEFAULT_BASE_URL;
					}
					String apiKey = System.getenv("SURE_AI_LLAMACPP_API_KEY");
					if (apiKey == null || apiKey.isBlank()) {
						apiKey = LlamaCppClient.DEFAULT_API_KEY;
					}
					c = new LlamaCppClient(AiConfig.builder().apiKey(apiKey).baseUrl(baseUrl).build());
					client = c;
				}
			}
		}
		return c;
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param model  模型名（需与 server 启动时 -a alias 一致）
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
	 * @param model 模型名（server 需以 --embedding 启动）
	 * @param text   文本
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
	 * 列出当前 server 加载的模型。
	 *
	 * @return 模型列表
	 */
	public static List<Model> listModels() {
		return client().listModels();
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
