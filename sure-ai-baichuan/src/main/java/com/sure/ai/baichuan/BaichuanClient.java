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

package com.sure.ai.baichuan;

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * 百川智能 Baichuan 开放平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://api.baichuan-ai.com/v1}（<b>带 {@code /v1}</b>），
 * 兼容引擎拼接 {@code /chat/completions}，即实际请求
 * {@code https://api.baichuan-ai.com/v1/chat/completions}；鉴权为
 * {@code Authorization: Bearer <apiKey>}。</p>
 *
 * <p>模型：{@link BaichuanModels#BAICHUAN4_TURBO}（官方 OpenAI 示例默认模型）、
 * {@link BaichuanModels#BAICHUAN4}、{@link BaichuanModels#BAICHUAN3_TURBO}。</p>
 *
 * <p>能力声明（1.4.0 P2-6）：仅支持对话与流式对话。百川的 OpenAI 兼容文档以
 * {@code /v1/chat/completions} 为准；其向量接口为原生 {@code /v1/embedding}（单数），
 * 与 OpenAI {@code /v1/embeddings} 路径不一致，未在本兼容端点声明 EMBED，
 * 调用 {@code embed} 时由基类 {@link #guard(Capability)} 快速失败。</p>
 *
 * <p>官方文档：<a href="https://platform.baichuan-ai.com/docs/api">
 * https://platform.baichuan-ai.com/docs/api</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class BaichuanClient extends OpenAiCompatClient {

	/** Baichuan 默认 baseUrl（带 /v1）。 */
	public static final String DEFAULT_BASE_URL = "https://api.baichuan-ai.com/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public BaichuanClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "baichuan";
	}

	/**
	 * 百川 OpenAI 兼容端点仅支持对话与流式对话；embedding/image/video/moderation/finetune 均未按 OpenAI 路径提供。
	 *
	 * @return 仅对话能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM);
	}
}
