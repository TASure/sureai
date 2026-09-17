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

package com.sure.ai.moonshot;

/**
 * Moonshot / Kimi 模型 ID 常量。
 *
 * <p>枚举 2026 年主力对话模型与向量模型；moonshot-v1-* 系列官方已标记 deprecated 但仍可调用，
 * 最新模型列表以官方文档为准。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class MoonshotModels {

	/** Kimi K2：MoE 旗舰对话模型。 */
	public static final String KIMI_K2 = "kimi-k2";

	/** Kimi K2.5：多模态理解模型。 */
	public static final String KIMI_K2_5 = "kimi-k2.5";

	/** moonshot-v1-8k：8K 上下文对话模型（旧版）。 */
	public static final String MOONSHOT_V1_8K = "moonshot-v1-8k";

	/** moonshot-v1-32k：32K 上下文对话模型（旧版）。 */
	public static final String MOONSHOT_V1_32K = "moonshot-v1-32k";

	/** moonshot-v1-128k：128K 长上下文对话模型（旧版）。 */
	public static final String MOONSHOT_V1_128K = "moonshot-v1-128k";

	/** moonshot-embedding-v1：文本向量模型。 */
	public static final String MOONSHOT_EMBEDDING_V1 = "moonshot-embedding-v1";

	/** 工具类禁止实例化。 */
	private MoonshotModels() {
		throw new AssertionError("No instances");
	}
}
