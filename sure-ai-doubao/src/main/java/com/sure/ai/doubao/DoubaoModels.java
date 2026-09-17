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

package com.sure.ai.doubao;

/**
 * 火山方舟（豆包 / Doubao）模型 ID 常量。
 *
 * <p><b>注意：</b>方舟实际调用时，请求体的 {@code model} 字段推荐传你在方舟控制台创建的
 * 推理接入点 ID（{@code ep-xxxxxxxx}），而不是直接传这里的模型 ID。这里收录的是常见模型 ID，
 * 便于对照与直连模型广场调用，最新列表以官方文档为准。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class DoubaoModels {

	/** doubao-1.5-pro-32k：豆包 1.5 Pro 32K 上下文。 */
	public static final String DOUBAO_1_5_PRO_32K = "doubao-1-5-pro-32k";

	/** doubao-1.5-lite-32k：豆包 1.5 Lite 32K 上下文。 */
	public static final String DOUBAO_1_5_LITE_32K = "doubao-1-5-lite-32k";

	/** doubao-pro-32k：豆包 Pro 32K 上下文。 */
	public static final String DOUBAO_PRO_32K = "doubao-pro-32k";

	/** doubao-pro-4k：豆包 Pro 4K 上下文。 */
	public static final String DOUBAO_PRO_4K = "doubao-pro-4k";

	/** doubao-embedding-text-240715：文本向量模型。 */
	public static final String DOUBAO_EMBEDDING_TEXT = "doubao-embedding-text-240715";

	/** doubao-seedance-2.5-260628：视频生成模型 2.5。 */
	public static final String SEEDANCE_2_5 = "doubao-seedance-2-5-260628";

	/** doubao-seedance-2.0-260128：视频生成模型 2.0。 */
	public static final String SEEDANCE_2_0 = "doubao-seedance-2-0-260128";

	/** seed-tts-2.0：语音合成资源 ID（X-Api-Resource-Id）。 */
	public static final String SEED_TTS_2_0 = "seed-tts-2.0";

	/** 豆包 TTS 默认音色。 */
	public static final String DOUBAO_TTS_SPEAKER_DEFAULT = "zh_female_vv_uranus_bigtts";

	/** volc.bigasr.auc：录音文件识别资源 ID（X-Api-Resource-Id）。 */
	public static final String VOLC_BIGASR_AUC = "volc.bigasr.auc";

	/** 工具类禁止实例化。 */
	private DoubaoModels() {
		throw new AssertionError("No instances");
	}
}
