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

package com.sure.ai.grok;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * xAI Grok 平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://api.x.ai/v1}（<b>带 {@code /v1}</b>），
 * 兼容引擎拼接 {@code /chat/completions}，即实际请求
 * {@code https://api.x.ai/v1/chat/completions}；鉴权为
 * {@code Authorization: Bearer <apiKey>}。模型列表为 {@code GET /v1/models}。</p>
 *
 * <p>模型：</p>
 * <ul>
 *   <li>{@link GrokModels#GROK_4_6} / {@link GrokModels#GROK_4_3}：最新旗舰对话模型；</li>
 *   <li>{@link GrokModels#GROK_4_20_REASONING} / {@link GrokModels#GROK_4_1_FAST_REASONING}：推理模型；</li>
 *   <li>{@link GrokModels#GROK_3} / {@link GrokModels#GROK_3_MINI}：上一代模型；</li>
 *   <li>{@link GrokModels#GROK_CODE_FAST_1}：代码补全模型。</li>
 * </ul>
 *
 * <p>Grok 特有参数 {@code reasoning_effort}（low/medium/high/xhigh，默认 high）由
 * {@link com.sure.ai.model.ChatRequest.Builder#reasoningEffort(String)} 直接透传，
 * 其余未建模字段可通过 {@link com.sure.ai.model.ChatRequest.Builder#extra(String, Object)} 透传。</p>
 *
 * <p>xAI <b>不提供 Embeddings API</b>，故 {@link #embed} 直接抛出 {@link AiException}。</p>
 *
 * <p>官方文档：<a href="https://docs.x.ai/">https://docs.x.ai/</a></p>
 *
 * @author sureai
 * @since 1.1.0
 */
public class GrokClient extends OpenAiCompatClient {

	/** Grok 默认 baseUrl（带 /v1）。 */
	public static final String DEFAULT_BASE_URL = "https://api.x.ai/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public GrokClient(AiConfig config) {
		super(applyDefaultBaseUrl(config, DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "grok";
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		throw new AiException("Grok (xAI) does not provide embeddings API");
	}

	/**
	 * baseUrl 为空时用默认地址替换（其余全部字段通过 {@link AiConfig#withBaseUrl} 原样保留）。
	 *
	 * @param config      原始配置
	 * @param defaultBase 默认 baseUrl
	 * @return 补齐 baseUrl 后的配置
	 */
	static AiConfig applyDefaultBaseUrl(AiConfig config, String defaultBase) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(defaultBase);
	}
}
