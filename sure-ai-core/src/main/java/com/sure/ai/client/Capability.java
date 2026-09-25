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

package com.sure.ai.client;

/**
 * 平台能力声明枚举（1.4.0 可维护性迭代 P2-6）。
 *
 * <p>平台 Client 通过覆写 {@link AbstractAiClient#capabilities()} 声明自身实际支持的能力集合；
 * 基类在「部分平台不支持」的能力方法入口调用 {@link AbstractAiClient#guard(Capability)}，
 * 使不支持该能力的平台在发请求前即抛出清晰的 {@link com.sure.ai.exception.AiException}，
 * 而非把请求发出去再收到 4xx。</p>
 *
 * <p>{@link #CHAT} 与 {@link #CHAT_STREAM} 是所有兼容平台都具备的核心能力，基类不对其加 guard；
 * 仅对「部分平台不支持」的能力（{@link #EMBED}/{@link #IMAGE}/{@link #VIDEO}/
 * {@link #MODERATION}/{@link #FINETUNE} 等）做快速失败。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public enum Capability {

	/** 同步对话（chat/completions）。 */
	CHAT,

	/** 流式对话（SSE chat/completions）。 */
	CHAT_STREAM,

	/** 向量嵌入（embeddings）。 */
	EMBED,

	/** 图像生成（images/generations）。 */
	IMAGE,

	/** 视频生成（异步任务）。 */
	VIDEO,

	/** 语音合成（TTS）。 */
	TTS,

	/** 语音识别（STT/转录）。 */
	STT,

	/** 重排（rerank）。 */
	RERANK,

	/** 批处理（batch 异步任务）。 */
	BATCH,

	/** 内容审核（moderations）。 */
	MODERATION,

	/** 微调（fine-tuning 任务 + 训练文件上传）。 */
	FINETUNE,

	/** Realtime（实时音频/事件流）。 */
	REALTIME,

	/** 函数调用（function calling，对话模型能力）。 */
	FUNCTION_CALLING,

	/** 工具调用（tool calling，对话模型能力）。 */
	TOOL_CALLING
}
