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

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

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
 * <p>能力声明（1.4.0 P2-6）：仅支持对话与流式对话。xAI <b>不提供 Embeddings API</b>，
 * 也不提供图像/视频/内容审核/微调端点；调用不支持方法时由基类 {@link #guard(Capability)}
 * 在发请求前快速失败。</p>
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
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "grok";
	}

	/**
	 * xAI 仅支持对话与流式对话；embedding/image/video/moderation/finetune 均未提供。
	 *
	 * @return 仅对话能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM);
	}
}
