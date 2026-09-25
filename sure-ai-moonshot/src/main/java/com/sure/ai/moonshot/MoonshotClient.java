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

package com.sure.ai.moonshot;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * 月之暗面 Moonshot / Kimi 开放平台客户端。
 *
 * <p>协议与鉴权均与 OpenAI 完全一致（Bearer apiKey），直接复用 {@link OpenAiCompatClient}。
 * 默认 baseUrl：{@code https://api.moonshot.cn/v1}。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class MoonshotClient extends OpenAiCompatClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://api.moonshot.cn/v1";

	/**
	 * 构造客户端；baseUrl 为空时使用 Moonshot 默认地址。
	 *
	 * @param config 配置
	 */
	public MoonshotClient(AiConfig config) {
		super(withDefaultBaseUrl(config));
	}

	@Override
	public String name() {
		return "moonshot";
	}

	/** baseUrl 为空时补默认地址，其余配置通过 {@link AiConfig#withBaseUrl} 原样保留。 */
	private static AiConfig withDefaultBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(DEFAULT_BASE_URL);
	}
}
