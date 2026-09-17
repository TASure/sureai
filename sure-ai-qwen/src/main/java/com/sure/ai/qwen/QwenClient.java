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

import java.util.Map;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * 阿里云百炼通义千问（DashScope）OpenAI 兼容模式客户端。
 *
 * <p>默认 baseUrl 为 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，
 * 兼容引擎拼接 {@code /chat/completions} 与 {@code /embeddings}；
 * 鉴权为 {@code Authorization: Bearer <sk-...>}。支持对话、SSE 流式与向量。</p>
 *
 * <p>官方文档：
 * <a href="https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope">通过 OpenAI 接口调用千问模型</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class QwenClient extends OpenAiCompatClient implements EmbeddingClient {

	/** DashScope 兼容模式默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public QwenClient(AiConfig config) {
		super(applyDefaultBaseUrl(config, DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "qwen";
	}

	/**
	 * baseUrl 为空时用默认地址重建配置。
	 *
	 * @param config      原始配置
	 * @param defaultBase 默认 baseUrl
	 * @return 补齐 baseUrl 后的配置
	 */
	static AiConfig applyDefaultBaseUrl(AiConfig config, String defaultBase) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(config.apiKey())
			.baseUrl(defaultBase)
			.timeout(config.timeout())
			.connectTimeout(config.connectTimeout())
			.proxy(config.proxy())
			.organization(config.organization())
			.maxRetries(config.maxRetries());
		for (Map.Entry<String, String> e : config.extraHeaders().entrySet()) {
			b.extraHeader(e.getKey(), e.getValue());
		}
		return b.build();
	}
}
