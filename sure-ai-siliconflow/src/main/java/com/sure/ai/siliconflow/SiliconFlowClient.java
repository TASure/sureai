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

package com.sure.ai.siliconflow;

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * 硅基流动（SiliconFlow / SiliconCloud）开放平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://api.siliconflow.cn/v1}（<b>带 {@code /v1}</b>，国内站），
 * 兼容引擎拼接 {@code /chat/completions} 与 {@code /embeddings}；鉴权为
 * {@code Authorization: Bearer <apiKey>}，无额外必需请求头。</p>
 *
 * <p>多区域端点（通过 {@link AiConfig.Builder#baseUrl(String)} 覆盖）：</p>
 * <ul>
 *   <li>国内站（默认）：{@code https://api.siliconflow.cn/v1}；</li>
 *   <li>国际站：{@code https://api.siliconflow.com/v1}。</li>
 * </ul>
 *
 * <p>模型：平台为开源模型聚合托管，{@code model} 形如 {@code 组织/模型名}，
 * 如 {@code deepseek-ai/DeepSeek-V3}、{@code Qwen/Qwen2.5-72B-Instruct}；
 * 向量模型如 {@code BAAI/bge-m3}。最新可用列表以官方模型库为准，
 * 常量见 {@link SiliconFlowModels}。推理模型的 {@code reasoning_content} 等特有字段
 * 由响应透传，无需客户端特殊处理。</p>
 *
 * <p>能力声明（1.9.0 批次 1b）：支持对话、流式对话与向量嵌入。官方 OpenAI 兼容文档同时提供
 * {@code /v1/chat/completions} 与 {@code /v1/embeddings}（见官方文档链接），故声明
 * {@link Capability#EMBED}；未提供 OpenAI 兼容的图像/视频/TTS/STT/内容审核/微调端点，
 * 调用这些方法时由基类 {@link #guard(Capability)} 在发请求前快速失败。</p>
 *
 * <p>官方文档：<a href="https://docs.siliconflow.cn/cn/userguide/capabilities/text-generation">
 * 语言模型（Chat Completions）</a>；
 * <a href="https://docs.siliconflow.cn/docs/api/embeddings-post">创建嵌入请求（Embeddings）</a>。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class SiliconFlowClient extends OpenAiCompatClient {

	/** SiliconFlow 默认 baseUrl（带 /v1，国内站）。 */
	public static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public SiliconFlowClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "siliconflow";
	}

	/**
	 * SiliconFlow OpenAI 兼容端点支持对话、流式对话与向量嵌入；
	 * 图像/视频/TTS/STT/moderation/finetune 均未在该兼容面提供。
	 *
	 * @return 对话 + 流式 + 向量能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM, Capability.EMBED);
	}
}
