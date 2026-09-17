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

package com.sure.ai.deepseek;

import java.util.Map;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * DeepSeek 平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://api.deepseek.com}（<b>不带 {@code /v1}</b>，
 * 兼容引擎会拼接 {@code /chat/completions}，即实际请求
 * {@code https://api.deepseek.com/chat/completions}）；鉴权为 {@code Authorization: Bearer <apiKey>}。</p>
 *
 * <p>模型：</p>
 * <ul>
 *   <li>{@link DeepSeekModels#DEEPSEEK_CHAT}：通用对话模型；</li>
 *   <li>{@link DeepSeekModels#DEEPSEEK_REASONER}：推理模型（深度思考），响应 message 中可能携带
 *   {@code reasoning_content} 字段，该字段会原样保留在 {@link com.sure.ai.model.ChatResponse#rawJson()} 中；
 *   其特有参数可通过 {@link com.sure.ai.model.ChatRequest.Builder#extra(String, Object)} 透传。</li>
 * </ul>
 *
 * <p>DeepSeek 暂无官方 embeddings API，故 {@link #embed} 直接抛出
 * {@link UnsupportedOperationException}。</p>
 *
 * <p>官方文档：<a href="https://api-docs.deepseek.com/">https://api-docs.deepseek.com/</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class DeepSeekClient extends OpenAiCompatClient {

	/** DeepSeek 默认 baseUrl（不带 /v1）。 */
	public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public DeepSeekClient(AiConfig config) {
		super(applyDefaultBaseUrl(config, DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "deepseek";
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		throw new UnsupportedOperationException("DeepSeek 暂不提供官方 embeddings API");
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
