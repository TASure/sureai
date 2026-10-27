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

package com.sure.ai.hunyuan;

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * 腾讯混元（Hunyuan）开放平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://api.hunyuan.cloud.tencent.com/v1}（<b>带 {@code /v1}</b>），
 * 兼容引擎拼接 {@code /chat/completions} 与 {@code /embeddings}；鉴权为
 * {@code Authorization: Bearer <apiKey>}，无额外必需请求头。</p>
 *
 * <p>迁移说明：腾讯混元大模型相关功能正逐步迁移至 <b>TokenHub</b>；新模型服务可改用
 * TokenHub 的 OpenAI 兼容端点 {@code https://tokenhub.tencentmaas.com/v1}
 * （通过 {@link AiConfig.Builder#baseUrl(String)} 覆盖）。</p>
 *
 * <p>模型：{@link HunyuanModels#HUNYUAN_TURBOS_LATEST} 为当前主力对话模型，
 * {@link HunyuanModels#HUNYUAN_T1_LATEST} 为推理模型，向量模型固定为
 * {@link HunyuanModels#HUNYUAN_EMBEDDING}（当前维度固定 1024）。混元特有参数
 * （如 {@code enable_enhancement}）可通过
 * {@link com.sure.ai.model.ChatRequest.Builder#extra(String, Object)} 透传。</p>
 *
 * <p>能力声明（1.9.0 批次 1b）：官方 OpenAI 兼容文档同时提供 {@code /v1/chat/completions}
 * 与 {@code /v1/embeddings}，故声明 {@link Capability#EMBED}；未提供 OpenAI 兼容的
 * 图像/视频/TTS/STT/内容审核/微调端点，调用这些方法时由基类
 * {@link #guard(Capability)} 在发请求前快速失败。</p>
 *
 * <p>官方文档：<a href="https://cloud.tencent.com/document/product/1729/111007">
 * 混元 OpenAI 兼容接口相关调用示例</a>。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class HunyuanClient extends OpenAiCompatClient {

	/** 混元默认 baseUrl（带 /v1）。 */
	public static final String DEFAULT_BASE_URL = "https://api.hunyuan.cloud.tencent.com/v1";

	/** TokenHub 迁移后的 OpenAI 兼容 baseUrl（参考，可经 baseUrl 覆盖使用）。 */
	public static final String TOKENHUB_BASE_URL = "https://tokenhub.tencentmaas.com/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public HunyuanClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "hunyuan";
	}

	/**
	 * 混元 OpenAI 兼容端点支持对话、流式对话与向量嵌入；
	 * 图像/视频/TTS/STT/moderation/finetune 均未在该兼容面提供。
	 *
	 * @return 对话 + 流式 + 向量能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM, Capability.EMBED);
	}
}
