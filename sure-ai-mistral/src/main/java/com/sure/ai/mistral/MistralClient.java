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

package com.sure.ai.mistral;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * Mistral AI 平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://api.mistral.ai/v1}（<b>带 {@code /v1}</b>），
 * 兼容引擎拼接 {@code /chat/completions}、{@code /embeddings}、{@code /models}；
 * 鉴权为 {@code Authorization: Bearer <apiKey>}。</p>
 *
 * <p>模型：</p>
 * <ul>
 *   <li>{@link MistralModels#MISTRAL_LARGE_LATEST} / {@link MistralModels#MISTRAL_MEDIUM_LATEST} /
 *   {@link MistralModels#MISTRAL_SMALL_LATEST}：对话模型；</li>
 *   <li>{@link MistralModels#CODESTRAL_LATEST}：代码补全模型；</li>
 *   <li>{@link MistralModels#MAGISTRAL_MEDIUM_LATEST}：开放权重推理模型；</li>
 *   <li>{@link MistralModels#MISTRAL_EMBED}：向量模型。</li>
 * </ul>
 *
 * <p>协议差异：Mistral 的 {@code tool_choice} 取值为 {@code "any"}（而非 OpenAI 的
 * {@code "required"}），随机种子字段为 {@code random_seed}（而非 {@code seed}）；
 * 这些差异均通过 {@link com.sure.ai.model.ChatRequest.Builder#extra(String, Object)} 透传，
 * 无需特殊处理。</p>
 *
 * <p>官方文档：<a href="https://docs.mistral.ai/">https://docs.mistral.ai/</a></p>
 *
 * @author sureai
 * @since 1.1.0
 */
public class MistralClient extends OpenAiCompatClient {

	/** Mistral 默认 baseUrl（带 /v1）。 */
	public static final String DEFAULT_BASE_URL = "https://api.mistral.ai/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public MistralClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "mistral";
	}
}
