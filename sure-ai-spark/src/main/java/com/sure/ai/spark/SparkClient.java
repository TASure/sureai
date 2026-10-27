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

package com.sure.ai.spark;

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * 讯飞星火（Spark）开放平台客户端（OpenAI 兼容协议）。
 *
 * <p>默认 baseUrl 为 {@code https://spark-api-open.xf-yun.com/v1}（<b>带 {@code /v1}</b>），
 * 兼容引擎拼接 {@code /chat/completions}；鉴权为 {@code Authorization: Bearer <apiKey>}。
 * 注意：讯飞的 API Key 形如 {@code APIPath:APIKey}（控制台"APIPath:APIKey"整体），
 * 调用方应将其作为 {@code apiKey} 整体传入，由本客户端原样放入 Bearer 头。</p>
 *
 * <p>模型：OpenAI 兼容端点使用短模型名 {@code lite}/{@code pro}/{@code max}/{@code general}
 * （见 {@link SparkModels}）；其中 {@code lite} 为永久免费模型。星火原生 WebSocket 接口
 * （{@code wss://spark-api.xf-yun.com/...}，按 domain 区分 generalv3.5/4.0Ultra 等）
 * 不在本 OpenAI 兼容模块范围内。</p>
 *
 * <p>能力声明（1.9.0 批次 1b）：仅支持对话与流式对话。讯飞 OpenAI 兼容端点仅提供
 * {@code /v1/chat/completions}，未在该兼容面提供 {@code /v1/embeddings} 等其余能力
 * （向量/内容审核为星火独立产品或原生接口）；调用 {@code embed} 等不支持方法时由基类
 * {@link #guard(Capability)} 在发请求前快速失败。</p>
 *
 * <p>官方文档：<a href="https://www.xfyun.cn/doc/spark/Web.html">星火认知大模型 Web API 文档</a>；
 * <a href="https://www.xfyun.cn/doc/spark/BatchAPI.html">批处理 API（模型名对照）</a>。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class SparkClient extends OpenAiCompatClient {

	/** 星火 OpenAI 兼容默认 baseUrl（带 /v1）。 */
	public static final String DEFAULT_BASE_URL = "https://spark-api-open.xf-yun.com/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置（apiKey 为控制台"APIPath:APIKey"整体）
	 */
	public SparkClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "spark";
	}

	/**
	 * 星火 OpenAI 兼容端点仅支持对话与流式对话；embedding/image/video/moderation/finetune 均未提供。
	 *
	 * @return 仅对话能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM);
	}
}
