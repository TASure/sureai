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

package com.sure.ai.cohere;

/**
 * Cohere v2 模型 ID 常量。
 *
 * <p>枚举 2026 年主力对话模型与通用向量模型；模型 ID 作为请求体 {@code model} 字段使用。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class CohereModels {

	/** command-a-plus-05-2026：Command A+ 旗舰对话模型。 */
	public static final String COMMAND_A_PLUS = "command-a-plus-05-2026";

	/** command-r-plus：Command R+ 高性能对话模型。 */
	public static final String COMMAND_R_PLUS = "command-r-plus";

	/** command-r：Command R 通用对话模型。 */
	public static final String COMMAND_R = "command-r";

	/** embed-v4.0：Cohere 通用文本向量模型。 */
	public static final String EMBED_V4 = "embed-v4.0";

	/** 工具类禁止实例化。 */
	private CohereModels() {
		throw new AssertionError("No instances");
	}
}
