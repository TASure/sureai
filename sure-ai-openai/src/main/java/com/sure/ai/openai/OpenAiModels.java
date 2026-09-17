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

/**
 * OpenAI 当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://platform.openai.com/docs/models">https://platform.openai.com/docs/models</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class OpenAiModels {

	private OpenAiModels() {
		throw new AssertionError("No instances");
	}

	/** GPT-4o：多模态旗舰对话模型。 */
	public static final String GPT_4O = "gpt-4o";

	/** GPT-4o mini：高性价比小尺寸对话模型。 */
	public static final String GPT_4O_MINI = "gpt-4o-mini";

	/** GPT-4 Turbo：长上下文前代旗舰。 */
	public static final String GPT_4_TURBO = "gpt-4-turbo";

	/** o1：推理模型（深度思考）。 */
	public static final String O1 = "o1";

	/** o1-mini：轻量化推理模型。 */
	public static final String O1_MINI = "o1-mini";

	/** text-embedding-3-small：轻量向量模型。 */
	public static final String TEXT_EMBEDDING_3_SMALL = "text-embedding-3-small";

	/** text-embedding-3-large：高精度向量模型。 */
	public static final String TEXT_EMBEDDING_3_LARGE = "text-embedding-3-large";

	/** DALL·E 3：旗舰文本生成图像模型（支持 revised_prompt）。 */
	public static final String DALL_E_3 = "dall-e-3";

	/** DALL·E 2：前代图像生成模型。 */
	public static final String DALL_E_2 = "dall-e-2";

	/** Sora 2：视频生成模型（异步任务，API 将于 2026-09-24 关闭）。 */
	public static final String SORA_2 = "sora-2";

	/** Sora 2 Pro：高清视频生成模型。 */
	public static final String SORA_2_PRO = "sora-2-pro";

	/** tts-1：标准语音合成模型。 */
	public static final String TTS_1 = "tts-1";

	/** tts-1-hd：高清语音合成模型。 */
	public static final String TTS_1_HD = "tts-1-hd";

	/** gpt-4o-mini-tts：GPT-4o mini 驱动的语音合成模型。 */
	public static final String GPT_4O_MINI_TTS = "gpt-4o-mini-tts";

	/** whisper-1：语音识别模型（Whisper large-v2）。 */
	public static final String WHISPER_1 = "whisper-1";

	/** gpt-4o-transcribe：GPT-4o 驱动的语音识别模型。 */
	public static final String GPT_4O_TRANSCRIBE = "gpt-4o-transcribe";

	/** gpt-4o-mini-transcribe：GPT-4o mini 驱动的语音识别模型。 */
	public static final String GPT_4O_MINI_TRANSCRIBE = "gpt-4o-mini-transcribe";
}
