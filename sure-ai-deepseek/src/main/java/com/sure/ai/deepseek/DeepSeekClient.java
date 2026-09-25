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

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

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
 * <p>能力声明（1.4.0 P2-6）：仅支持对话与流式对话。DeepSeek 暂无官方 embeddings API，
 * 也不提供图像/视频/内容审核/微调端点；调用 {@code embed} 等不支持方法时由基类
 * {@link #guard(Capability)} 在发请求前快速失败，不再依赖本类手工抛异常。</p>
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
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "deepseek";
	}

	/**
	 * DeepSeek 仅支持对话与流式对话；embedding/image/video/moderation/finetune 均未提供。
	 *
	 * @return 仅对话能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM);
	}
}
