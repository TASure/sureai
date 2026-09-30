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

package com.sure.ai.lingyi;

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * 01.AI 零一万物（Lingyiwanwu / Yi）开放平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://api.lingyiwanwu.com/v1}（<b>带 {@code /v1}</b>），
 * 兼容引擎拼接 {@code /chat/completions}，即实际请求
 * {@code https://api.lingyiwanwu.com/v1/chat/completions}；鉴权为
 * {@code Authorization: Bearer <apiKey>}。官方声明 API 与 OpenAI 完全兼容。</p>
 *
 * <p>模型：{@link LingyiModels#YI_LARGE}（旗舰）、{@link LingyiModels#YI_MEDIUM}、
 * {@link LingyiModels#YI_LIGHTNING}（高速推理）。</p>
 *
 * <p>能力声明（1.4.0 P2-6）：仅支持对话与流式对话。零一万物 OpenAI 兼容文档以
 * {@code /v1/chat/completions} 为准，未提供 {@code /v1/embeddings} 等其余能力；
 * 调用 {@code embed} 等不支持方法时由基类 {@link #guard(Capability)} 在发请求前快速失败。</p>
 *
 * <p>官方文档：<a href="https://platform.lingyiwanwu.com/docs/api-reference">
 * https://platform.lingyiwanwu.com/docs/api-reference</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class LingyiClient extends OpenAiCompatClient {

	/** 01.AI 零一万物默认 baseUrl（带 /v1）。 */
	public static final String DEFAULT_BASE_URL = "https://api.lingyiwanwu.com/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public LingyiClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "lingyi";
	}

	/**
	 * 零一万物 OpenAI 兼容端点仅支持对话与流式对话；embedding/image/video/moderation/finetune 均未提供。
	 *
	 * @return 仅对话能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM);
	}
}
