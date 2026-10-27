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

package com.sure.ai.zhipu;

/**
 * 智谱 GLM 模型 ID 常量。
 *
 * <p>枚举 2026 年仍在 OpenAI 兼容路径（{@code /chat/completions}、{@code /embeddings}）上可用的主力模型；
 * 最新模型列表以官方文档为准。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class ZhipuModels {

	/** GLM-4-Plus：旗舰高智能对话模型。 */
	public static final String GLM_4_PLUS = "glm-4-plus";

	/** GLM-4-Flash：免费/高速轻量对话模型。 */
	public static final String GLM_4_FLASH = "glm-4-flash";

	/** GLM-4-Air：性价比均衡的通用对话模型。 */
	public static final String GLM_4_AIR = "glm-4-air";

	/** GLM-4-Long：长上下文对话模型。 */
	public static final String GLM_4_LONG = "glm-4-long";

	/** Embedding-3：文本向量模型。 */
	public static final String EMBEDDING_3 = "embedding-3";

	/** CogView-3：文本生成图像模型。 */
	public static final String COGVIEW_3 = "cogview-3";

	/** CogView-3-Plus：增强版图像生成模型。 */
	public static final String COGVIEW_3_PLUS = "cogview-3-plus";

	/** CogView-4：新一代图像生成模型（250304 快照）。 */
	public static final String COGVIEW_4 = "cogview-4-250304";

	/** GLM-Image：多模态图像理解/生成模型。 */
	public static final String GLM_IMAGE = "glm-image";

	/** CogView-3-Flash：免费/高速轻量图像生成模型。 */
	public static final String COGVIEW_3_FLASH = "cogview-3-flash";

	/** CogVideoX-3：新一代视频生成模型。 */
	public static final String COGVIDEOX_3 = "cogvideox-3";

	/** CogVideoX-2：上一代视频生成模型。 */
	public static final String COGVIDEOX_2 = "cogvideox-2";

	/** CogVideoX-Flash：免费/高速轻量视频生成模型。 */
	public static final String COGVIDEOX_FLASH = "cogvideox-flash";

	/** GLM-TTS：语音合成模型。 */
	public static final String GLM_TTS = "glm-tts";

	/** GLM-ASR-2512：语音识别模型（2512 快照）。 */
	public static final String GLM_ASR_2512 = "glm-asr-2512";

	/** 工具类禁止实例化。 */
	private ZhipuModels() {
		throw new AssertionError("No instances");
	}
}
