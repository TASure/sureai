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

package com.sure.ai.deepseek;

/**
 * DeepSeek 当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://api-docs.deepseek.com/quick_start/pricing">https://api-docs.deepseek.com/quick_start/pricing</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class DeepSeekModels {

	private DeepSeekModels() {
		throw new AssertionError("No instances");
	}

	/** deepseek-chat：通用对话模型（非思考模式）。 */
	public static final String DEEPSEEK_CHAT = "deepseek-chat";

	/** deepseek-reasoner：推理模型（深度思考，响应含 reasoning_content）。 */
	public static final String DEEPSEEK_REASONER = "deepseek-reasoner";
}
