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

package com.sure.ai.mistral;

/**
 * Mistral AI 当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://docs.mistral.ai/getting-started/models/models_overview/">
 * https://docs.mistral.ai/getting-started/models/models_overview/</a></p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class MistralModels {

	private MistralModels() {
		throw new AssertionError("No instances");
	}

	/** mistral-large-latest：最新旗舰对话模型。 */
	public static final String MISTRAL_LARGE_LATEST = "mistral-large-latest";

	/** mistral-medium-latest：中等规模对话模型。 */
	public static final String MISTRAL_MEDIUM_LATEST = "mistral-medium-latest";

	/** mistral-small-latest：轻量高速对话模型。 */
	public static final String MISTRAL_SMALL_LATEST = "mistral-small-latest";

	/** codestral-latest：代码补全/生成模型。 */
	public static final String CODESTRAL_LATEST = "codestral-latest";

	/** magistral-medium-latest：开放权重推理模型。 */
	public static final String MAGISTRAL_MEDIUM_LATEST = "magistral-medium-latest";

	/** mistral-embed：文本向量模型。 */
	public static final String MISTRAL_EMBED = "mistral-embed";
}
