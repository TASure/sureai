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

package com.sure.ai.azure;

/**
 * Azure OpenAI 常见部署对应模型 ID 参考。
 *
 * <p>注意：Azure 请求中的 {@code model} 字段实际是<b>部署名（deployment）</b>，需在
 * Azure Portal 创建部署后获得；下面常量仅为常见部署对应的 OpenAI 模型名，作为便捷参考。
 * 可向 {@code model} 字段传入任意部署名字符串。最新模型列表以官方文档为准：
 * <a href="https://learn.microsoft.com/en-us/azure/ai-foundry/openai/concepts/models">Azure OpenAI models</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class AzureModels {

	private AzureModels() {
		throw new AssertionError("No instances");
	}

	/** GPT-4o 部署参考名。 */
	public static final String GPT_4O = "gpt-4o";

	/** GPT-4o mini 部署参考名。 */
	public static final String GPT_4O_MINI = "gpt-4o-mini";

	/** GPT-4 Turbo 部署参考名。 */
	public static final String GPT_4_TURBO = "gpt-4-turbo";

	/** text-embedding-3-small 部署参考名。 */
	public static final String TEXT_EMBEDDING_3_SMALL = "text-embedding-3-small";

	/** text-embedding-3-large 部署参考名。 */
	public static final String TEXT_EMBEDDING_3_LARGE = "text-embedding-3-large";

	/** DALL·E 3 部署参考名。 */
	public static final String DALL_E_3 = "dall-e-3";

	/** Sora 2 视频生成模型（预览）。 */
	public static final String SORA_2 = "sora-2";

	// ==================== TTS 音色 ====================

	/** zh-CN XiaoxiaoNeural（晓晓，女声）。 */
	public static final String TTS_VOICE_XIAOXIAO = "zh-CN-XiaoxiaoNeural";

	/** zh-CN YunxiNeural（云希，男声）。 */
	public static final String TTS_VOICE_YUNXI = "zh-CN-YunxiNeural";

	/** TTS 输出格式：24kHz 48kbps 单声道 mp3。 */
	public static final String TTS_OUTPUT_MP3 = "audio-24khz-48kbitrate-mono-mp3";
}
