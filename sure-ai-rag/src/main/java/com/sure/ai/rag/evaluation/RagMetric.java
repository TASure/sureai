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

package com.sure.ai.rag.evaluation;

/**
 * RAG 评估指标：对单条 {@link RagTrace} 打 [0,1] 分。
 *
 * <p>取值约定：</p>
 * <ul>
 *   <li>0.0 ~ 1.0：指标分数，越高越好；</li>
 *   <li>{@link Double#NaN}：该指标对当前轨迹不适用（如 context recall 缺参考答案），
 *       由 {@link RagEvaluator} 归入 notApplicable，不计入均值。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.8.0
 */
public interface RagMetric {

	/**
	 * 指标名（结果 Map 的键，如 {@code "faithfulness"}）。
	 *
	 * @return 指标名
	 */
	String name();

	/**
	 * 评估单条轨迹。
	 *
	 * @param trace 轨迹
	 * @return [0,1] 分数，或 {@link Double#NaN} 表示不适用
	 */
	double evaluate(RagTrace trace);
}
