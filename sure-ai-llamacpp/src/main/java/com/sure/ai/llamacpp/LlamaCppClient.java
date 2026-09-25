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

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * llama.cpp 本地推理服务器客户端。
 *
 * <p>完全 OpenAI 兼容协议：{@code POST /v1/chat/completions}、
 * {@code POST /v1/embeddings}、{@code GET /v1/models}，SSE 流式。
 * 默认 baseUrl 为 {@code http://localhost:8080/v1}；默认无鉴权，
 * 若启动时通过 {@code --api-key} 设置了密钥则需在配置中传入对应 Bearer Token。</p>
 *
 * <p><b>向量（embedding）注意：</b>llama.cpp server 需以 {@code --embedding} 参数启动
 * 才会开启 {@code /v1/embeddings} 端点，否则该请求将返回 404。</p>
 *
 * <p>官方文档：<a href="https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md">llama.cpp server</a></p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class LlamaCppClient extends OpenAiCompatClient {

	/** llama.cpp server 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:8080/v1";

	/** 默认占位 API Key（本地无鉴权时发送，server 端忽略）。 */
	public static final String DEFAULT_API_KEY = "dummy";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置（baseUrl 为空时使用默认本地地址）
	 */
	public LlamaCppClient(AiConfig config) {
		super(applyDefaultBaseUrl(config, DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "llamacpp";
	}

	@Override
	protected void applyAuth(java.net.http.HttpRequest.Builder requestBuilder, AiConfig cfg) {
		String key = cfg.apiKey();
		// 默认占位 key 或空值时不发送鉴权头（llama.cpp server 默认无鉴权）
		if (key != null && !key.isBlank() && !DEFAULT_API_KEY.equals(key)) {
			requestBuilder.header("Authorization", "Bearer " + key);
		}
	}

	/**
	 * baseUrl 为空时，用默认地址替换（其余全部字段通过 {@link AiConfig#withBaseUrl} 原样保留）。
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
