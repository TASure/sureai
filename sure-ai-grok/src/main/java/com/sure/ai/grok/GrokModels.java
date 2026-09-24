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

package com.sure.ai.grok;

/**
 * xAI Grok 当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://docs.x.ai/docs/models">https://docs.x.ai/docs/models</a></p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class GrokModels {

	private GrokModels() {
		throw new AssertionError("No instances");
	}

	/** grok-4.6：最新旗舰对话模型。 */
	public static final String GROK_4_6 = "grok-4.6";

	/** grok-4.3：上一代旗舰对话模型。 */
	public static final String GROK_4_3 = "grok-4.3";

	/** grok-4-20-reasoning：推理模型（2025 推理版）。 */
	public static final String GROK_4_20_REASONING = "grok-4-20-reasoning";

	/** grok-4-1-fast-reasoning：高速推理模型。 */
	public static final String GROK_4_1_FAST_REASONING = "grok-4-1-fast-reasoning";

	/** grok-3：第三代通用对话模型。 */
	public static final String GROK_3 = "grok-3";

	/** grok-3-mini：第三代轻量模型（低成本高速）。 */
	public static final String GROK_3_MINI = "grok-3-mini";

	/** grok-code-fast-1：代码生成/补全模型。 */
	public static final String GROK_CODE_FAST_1 = "grok-code-fast-1";
}
