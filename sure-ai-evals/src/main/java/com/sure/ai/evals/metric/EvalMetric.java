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

import com.sure.ai.evals.dataset.EvalCase;

/**
 * 通用评估指标 SPI：对单条用例的实际输出打归一化分并给出理由。
 *
 * <p>实现方覆盖 {@link #evaluate(EvalCase, EvalOutcome, EvalContext)}：</p>
 * <ul>
 *   <li>确定性指标（精确匹配 / 包含 / 正则 / JSON Schema）—— 无需模型，纯函数判定；</li>
 *   <li>语义指标（Embedding 余弦相似度）—— 经 {@code EmbeddingClient} 计算；</li>
 *   <li>LLM 裁判（二元 rubric / 1-5 / 百分制 / pairwise）—— 经 {@code AiClient} 调用；</li>
 *   <li>运行指标（延迟 / token 成本）—— 从 {@link EvalOutcome} 与 usage 统计。</li>
 * </ul>
 *
 * <p>指标对当前用例不适用时应返回 {@link MetricResult#notApplicable(String)}（NaN），
 * 聚合时剔除；任何模型调用异常须在指标内部容错，不得向上抛出中断批量评估。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public interface EvalMetric {

	/**
	 * 指标名（结果 Map 的键，如 {@code "exact_match"}、{@code "llm_judge"}）。
	 *
	 * @return 指标名
	 */
	String name();

	/**
	 * 评估单条用例。
	 *
	 * @param evalCase 用例
	 * @param outcome  实际执行结果
	 * @param context  运行上下文
	 * @return 指标结果（[0,1] 或 NaN + 理由）
	 */
	MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context);

	/**
	 * 通过阈值：分数 ≥ 该值记为通过。
	 *
	 * <p>二元指标默认 1.0（必须完全通过）；打分类指标可覆盖为更低值（如 0.8）。</p>
	 *
	 * @return 阈值，[0,1]
	 */
	default double passThreshold() {
		return 1.0;
	}
}
