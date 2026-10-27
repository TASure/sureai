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

package com.sure.ai.minimax;

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * MiniMax（稀宇科技）开放平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://api.minimax.cn/v1}（<b>带 {@code /v1}</b>，国内站），
 * 兼容引擎拼接 {@code /chat/completions}，即实际请求
 * {@code https://api.minimax.cn/v1/chat/completions}；鉴权为
 * {@code Authorization: Bearer <apiKey>}，无额外必需请求头。</p>
 *
 * <p>多区域端点（通过 {@link AiConfig.Builder#baseUrl(String)} 覆盖）：</p>
 * <ul>
 *   <li>国内站（默认）：{@code https://api.minimax.cn/v1}；</li>
 *   <li>国际站：{@code https://api.minimax.io/v1}；</li>
 *   <li>历史域名 {@code https://api.minimaxi.com/v1}、{@code https://api.minimax.chat/v1}
 *   仍按 OpenAI 兼容路径提供服务。</li>
 * </ul>
 *
 * <p>模型：{@link MiniMaxModels#MINIMAX_M3} 为最新旗舰（1M 上下文，支持 Agent 推理/工具调用/
 * 多模态输入），另有 M2.x 系列见 {@link MiniMaxModels}。MiniMax-M3 的 thinking 开关
 * （{@code thinking.type=adaptive/disabled}）、{@code reasoning_split} 等特有参数可通过
 * {@link com.sure.ai.model.ChatRequest.Builder#extra(String, Object)} 透传。</p>
 *
 * <p>能力声明（1.4.0 P2-6）：仅支持对话与流式对话。MiniMax 的 OpenAI 兼容文档仅提供
 * {@code /v1/chat/completions}，未在该兼容端点提供 {@code /v1/embeddings} 等其余能力；
 * 调用 {@code embed} 等不支持方法时由基类 {@link #guard(Capability)} 在发请求前快速失败。</p>
 *
 * <p>官方文档：<a href="https://platform.minimaxi.com/docs/api-reference/text-openai-api">
 * https://platform.minimaxi.com/docs/api-reference/text-openai-api</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class MiniMaxClient extends OpenAiCompatClient {

	/** MiniMax 默认 baseUrl（带 /v1，国内站）。 */
	public static final String DEFAULT_BASE_URL = "https://api.minimax.cn/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public MiniMaxClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "minimax";
	}

	/**
	 * MiniMax OpenAI 兼容端点仅支持对话与流式对话；embedding/image/video/moderation/finetune 均未提供。
	 *
	 * @return 仅对话能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM);
	}
}
