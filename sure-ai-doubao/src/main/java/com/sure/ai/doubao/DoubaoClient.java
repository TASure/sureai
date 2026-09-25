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

package com.sure.ai.doubao;

import java.util.List;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.Model;

/**
 * 火山方舟（豆包 / Doubao）客户端。
 *
 * <p>方舟推理服务兼容 OpenAI 协议（Bearer apiKey），直接复用 {@link OpenAiCompatClient}。
 * 默认 baseUrl：{@code https://ark.cn-beijing.volces.com/api/v3}。请求体中的 {@code model}
 * 应传你在方舟控制台创建的推理接入点 ID（{@code ep-xxx}），也可直接传模型 ID。</p>
 *
 * <p>P2 平台特定能力：</p>
 * <ul>
 *   <li><b>模型列表</b>：方舟无 OpenAI 风格 {@code GET /models}，走 IAMS 签名
 *   {@code ListEndpoints}/{@code ListCustomModels}，{@link #listModels()} 直接抛
 *   {@link AiException}。</li>
 *   <li><b>思考模式 / Grounding</b>：方舟兼容 OpenAI 协议，{@code reasoning_effort}
 *   与 {@code web_search} 工具由 core 序列化，无需额外改写。</li>
 *   <li><b>微调</b>：方舟微调为控制台/异步任务流程，无稳定公开 REST 端点，本期未单独适配。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
public class DoubaoClient extends OpenAiCompatClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

	/**
	 * 构造客户端；baseUrl 为空时使用火山方舟默认地址。
	 *
	 * @param config 配置
	 */
	public DoubaoClient(AiConfig config) {
		super(withDefaultBaseUrl(config));
	}

	@Override
	public String name() {
		return "doubao";
	}

	/**
	 * 火山方舟不提供 OpenAI 风格的 {@code GET /models} 列表；模型/推理接入点需走
	 * IAMS 签名的 {@code ListEndpoints}/{@code ListCustomModels} Action，鉴权与 OpenAI
	 * Bearer 协议差异巨大，故此处直接抛出业务异常，引导调用方到方舟控制台查看接入点。
	 *
	 * @return 从不正常返回
	 * @throws AiException 始终抛出，说明模型列表需 IAMS 签名
	 */
	@Override
	public List<Model> listModels() {
		throw new AiException("Doubao models list requires Volcengine IAMS signature; "
			+ "use console instead");
	}

	/** baseUrl 为空时补默认地址，其余配置通过 {@link AiConfig#withBaseUrl} 原样保留。 */
	private static AiConfig withDefaultBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(DEFAULT_BASE_URL);
	}
}
