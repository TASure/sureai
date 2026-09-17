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

package com.sure.ai.qwen;

/**
 * 阿里云百炼通义千问当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://help.aliyun.com/zh/model-studio/getting-started/models">通义千问模型列表</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class QwenModels {

	private QwenModels() {
		throw new AssertionError("No instances");
	}

	/** qwen-max：旗舰对话模型。 */
	public static final String QWEN_MAX = "qwen-max";

	/** qwen-plus：均衡性价比对话模型。 */
	public static final String QWEN_PLUS = "qwen-plus";

	/** qwen-turbo：高并发低延迟对话模型。 */
	public static final String QWEN_TURBO = "qwen-turbo";

	/** qwen-long：长上下文对话模型。 */
	public static final String QWEN_LONG = "qwen-long";

	/** text-embedding-v3：通用文本向量 v3。 */
	public static final String TEXT_EMBEDDING_V3 = "text-embedding-v3";

	/** text-embedding-v2：通用文本向量 v2。 */
	public static final String TEXT_EMBEDDING_V2 = "text-embedding-v2";

	/** wanx-v1：通义万相初代文生图模型。 */
	public static final String WANX_V1 = "wanx-v1";

	/** wan2.1-t2i-turbo：通义万相 2.1 文生图 Turbo，高并发低延迟。 */
	public static final String WAN2_1_T2I_TURBO = "wan2.1-t2i-turbo";

	/** wan2.1-t2i-plus：通义万相 2.1 文生图 Plus，质量更高。 */
	public static final String WAN2_1_T2I_PLUS = "wan2.1-t2i-plus";

	/** wan2.6-t2v：通义万相 2.6 文生视频。 */
	public static final String WAN2_6_T2V = "wan2.6-t2v";

	/** wan2.5-t2v：通义万相 2.5 文生视频。 */
	public static final String WAN2_5_T2V = "wan2.5-t2v";

	/** wanx2.1-t2v-turbo：通义万相 2.1 文生视频 Turbo，高并发低延迟。 */
	public static final String WANX2_1_T2V = "wanx2.1-t2v-turbo";

	/** cosyvoice-v3.5-plus：CosyVoice 语音合成 Plus。 */
	public static final String COSYVOICE_V3_5_PLUS = "cosyvoice-v3.5-plus";

	/** cosyvoice-v3.5-flash：CosyVoice 语音合成 Flash，低延迟。 */
	public static final String COSYVOICE_V3_5_FLASH = "cosyvoice-v3.5-flash";

	/** qwen-audio-3.0-tts-plus：Qwen-Audio TTS Plus。 */
	public static final String QWEN_AUDIO_TTS_PLUS = "qwen-audio-3.0-tts-plus";

	/** qwen3-asr-flash：Qwen3 语音识别 Flash。 */
	public static final String QWEN3_ASR_FLASH = "qwen3-asr-flash";
}
