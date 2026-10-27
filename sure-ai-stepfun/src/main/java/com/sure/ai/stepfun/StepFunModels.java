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

package com.sure.ai.stepfun;

/**
 * 阶跃星辰 StepFun 当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://platform.stepfun.com/docs/zh/pricing/details">
 * https://platform.stepfun.com/docs/zh/pricing/details</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class StepFunModels {

	private StepFunModels() {
		throw new AssertionError("No instances");
	}

	/** step-5-preview：最新旗舰 MoE 对话模型。 */
	public static final String STEP_5_PREVIEW = "step-5-preview";

	/** step-2-mini：推荐高性价比轻量对话模型。 */
	public static final String STEP_2_MINI = "step-2-mini";

	/** step-2-16k：16K 上下文对话模型。 */
	public static final String STEP_2_16K = "step-2-16k";
}
