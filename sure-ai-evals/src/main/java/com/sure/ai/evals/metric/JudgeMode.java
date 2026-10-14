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

package com.sure.ai.evals.metric;

/**
 * LLM-as-judge 评分模式。
 *
 * <p>每种模式约定裁判模型的输出格式与归一化口径：</p>
 * <ul>
 *   <li>{@link #BINARY}：按 rubric 输出 yes/no，归一化为 1.0 / 0.0；</li>
 *   <li>{@link #SCALE_1_TO_5}：裁判输出 1~5 整数，归一化为 {@code (n-1)/4}；</li>
 *   <li>{@link #SCORE_100}：裁判输出 0~100 分数，归一化为 {@code n/100}；</li>
 *   <li>{@link #PAIRWISE}：实际输出（A）对参考答案（B）对比，
 *       A 胜 1.0、平 0.5、B 胜 0.0。</li>
 * </ul>
 *
 * @author sureai
 * @since 2.8.0
 */
public enum JudgeMode {

	/** 二元 rubric 判定。 */
	BINARY,

	/** 1~5 分量表。 */
	SCALE_1_TO_5,

	/** 百分制打分。 */
	SCORE_100,

	/** A/B 成对对比。 */
	PAIRWISE
}
