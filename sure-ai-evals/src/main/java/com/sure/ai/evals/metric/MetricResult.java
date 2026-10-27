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
 * 单条用例的指标结果：归一化分数 + 判定理由。
 *
 * <p>取值约定：</p>
 * <ul>
 *   <li>{@code 0.0 ~ 1.0}：指标分数，越高越好（二元指标只取 0 / 1）；</li>
 *   <li>{@link Double#NaN}：该指标对当前用例不适用（如缺参考答案、缺 usage），
 *       聚合时剔除，不计入均值。</li>
 * </ul>
 *
 * <p>构造时分数自动 clamp 到 [0,1]（NaN 保留），{@code reason} 为 null 时归一为空串。
 * 是否「通过」由指标的 {@code passThreshold} 与 {@link #passedAt(double)} 决定，
 * 结果对象本身不持有阈值，便于同一结果在不同阈值下复用。</p>
 *
 * @param score  归一化分数 [0,1]，或 NaN 表示不适用
 * @param reason 判定理由（裁判说明 / 不匹配片段 / 不适用原因），非 null
 * @author sureai
 * @since 2.8.0
 */
public record MetricResult(double score, String reason) {

	/**
	 * 紧凑构造器：clamp 分数并归一化理由。
	 */
	public MetricResult {
		if (!Double.isNaN(score)) {
			if (score < 0.0) {
				score = 0.0;
			} else if (score > 1.0) {
				score = 1.0;
			}
		}
		reason = reason == null ? "" : reason;
	}

	/**
	 * 构造打分结果。
	 *
	 * @param score  分数（自动 clamp）
	 * @param reason 理由
	 * @return 结果
	 */
	public static MetricResult of(double score, String reason) {
		return new MetricResult(score, reason);
	}

	/**
	 * 二元通过（score=1）。
	 *
	 * @param reason 理由
	 * @return 结果
	 */
	public static MetricResult pass(String reason) {
		return new MetricResult(1.0, reason);
	}

	/**
	 * 二元失败（score=0）。
	 *
	 * @param reason 理由
	 * @return 结果
	 */
	public static MetricResult fail(String reason) {
		return new MetricResult(0.0, reason);
	}

	/**
	 * 不适用（score=NaN）。
	 *
	 * @param reason 不适用原因
	 * @return 结果
	 */
	public static MetricResult notApplicable(String reason) {
		return new MetricResult(Double.NaN, reason);
	}

	/**
	 * 是否适用（非 NaN）。
	 *
	 * @return true 表示适用
	 */
	public boolean applicable() {
		return !Double.isNaN(this.score);
	}

	/**
	 * 在给定阈值下是否通过。
	 *
	 * @param threshold 通过阈值（[0,1]）
	 * @return true 表示适用且分数 ≥ 阈值
	 */
	public boolean passedAt(double threshold) {
		return applicable() && this.score >= threshold;
	}
}
