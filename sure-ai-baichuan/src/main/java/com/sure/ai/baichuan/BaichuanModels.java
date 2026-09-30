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

package com.sure.ai.baichuan;

/**
 * 百川智能 Baichuan 当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://platform.baichuan-ai.com/docs/api">
 * https://platform.baichuan-ai.com/docs/api</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class BaichuanModels {

	private BaichuanModels() {
		throw new AssertionError("No instances");
	}

	/** Baichuan4-Turbo：当前推荐旗舰对话模型（官方 OpenAI 兼容示例默认模型）。 */
	public static final String BAICHUAN4_TURBO = "Baichuan4-Turbo";

	/** Baichuan4：第四代旗舰对话模型。 */
	public static final String BAICHUAN4 = "Baichuan4";

	/** Baichuan3-Turbo：第三代 Turbo 对话模型。 */
	public static final String BAICHUAN3_TURBO = "Baichuan3-Turbo";
}
