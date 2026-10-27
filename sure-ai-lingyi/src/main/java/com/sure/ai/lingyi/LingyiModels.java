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

package com.sure.ai.lingyi;

/**
 * 01.AI 零一万物 Yi 系列当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://platform.lingyiwanwu.com/docs/api-reference">
 * https://platform.lingyiwanwu.com/docs/api-reference</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class LingyiModels {

	private LingyiModels() {
		throw new AssertionError("No instances");
	}

	/** yi-large：旗舰对话模型。 */
	public static final String YI_LARGE = "yi-large";

	/** yi-medium：中等规格对话模型。 */
	public static final String YI_MEDIUM = "yi-medium";

	/** yi-lightning：高速推理对话模型。 */
	public static final String YI_LIGHTNING = "yi-lightning";
}
