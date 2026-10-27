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

package com.sure.ai.openai;

import java.util.Set;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * OpenAI 官方平台客户端。
 *
 * <p>直接复用 {@link OpenAiCompatClient} 的 OpenAI 兼容协议：默认 baseUrl 为
 * {@code https://api.openai.com/v1}；鉴权头为 {@code Authorization: Bearer <apiKey>}，
 * 当 {@code config.organization} 非空时额外携带 {@code OpenAI-Organization} 头。
 * 支持对话、SSE 流式对话与向量（embeddings）。</p>
 *
 * <p>官方文档：<a href="https://platform.openai.com/docs/api-reference">https://platform.openai.com/docs/api-reference</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class OpenAiClient extends OpenAiCompatClient {

	/** OpenAI 官方默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public OpenAiClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	@Override
	public String name() {
		return "openai";
	}

	/**
	 * OpenAI 为 OpenAI 兼容协议的参考平台，基类实现的全部端点在官方 API 上均存在：
	 * chat/completions、embeddings（text-embedding-3 系列）、images/generations（gpt-image-1/DALL·E）、
	 * audio/speech（tts-1）、audio/transcriptions（whisper-1）、moderations（text-moderation）、
	 * fine_tuning/jobs 以及异步视频任务（POST /videos + GET /videos/{id} 轮询）。
	 *
	 * <p>逐项依据官方 API 参考：<a href="https://platform.openai.com/docs/api-reference">platform.openai.com/docs/api-reference</a>。
	 * 视频走基类 {@code /videos} 异步任务协议（Sora），本主 Client 直接复用，故声明 VIDEO。</p>
	 *
	 * @return 全量兼容能力集合
	 */
	@Override
	protected Set<Capability> capabilities() {
		return Set.of(Capability.CHAT, Capability.CHAT_STREAM, Capability.EMBED,
			Capability.IMAGE, Capability.VIDEO, Capability.TTS, Capability.STT,
			Capability.MODERATION, Capability.FINETUNE);
	}
}
