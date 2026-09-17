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

package com.sure.ai.gemini;

/**
 * Google Gemini 模型 ID 常量。
 *
 * <p>收录 2026 年当前可用的主力模型；可传入任意模型字符串，
 * 以官方文档模型列表为准：
 * <a href="https://ai.google.dev/gemini-api/docs/models">Gemini models</a>。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class GeminiModels {

	/** Gemini 2.5 Pro — 最强推理与多模态。 */
	public static final String GEMINI_2_5_PRO = "gemini-2.5-pro";

	/** Gemini 2.5 Flash — 均衡性能与速度。 */
	public static final String GEMINI_2_5_FLASH = "gemini-2.5-flash";

	/** Gemini 2.5 Flash-Lite — 低成本高吞吐。 */
	public static final String GEMINI_2_5_FLASH_LITE = "gemini-2.5-flash-lite";

	/** Gemini 2.0 Flash — 上一代稳定快速模型。 */
	public static final String GEMINI_2_0_FLASH = "gemini-2.0-flash";

	/** Gemini Embedding — 向量嵌入模型。 */
	public static final String GEMINI_EMBEDDING_001 = "gemini-embedding-001";

	private GeminiModels() {
		throw new AssertionError("No instances");
	}
}
