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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估结果：每条轨迹（或批量汇总）的各指标分数、不适用指标与综合均值。
 *
 * @param scores 指标名 → [0,1] 分数（不适用指标不在此 Map 中）
 * @param notApplicable 不适用指标名列表（如缺参考答案的 context recall）
 * @param overall 适用指标分数的算术均值；无适用指标时为 {@link Double#NaN}
 * @author sureai
 * @since 1.8.0
 */
public record EvaluationResult(Map<String, Double> scores, List<String> notApplicable,
		double overall) {

	/**
	 * 紧凑构造器：防御性拷贝。
	 */
	public EvaluationResult {
		scores = scores == null ? Map.of() : Map.copyOf(scores);
		notApplicable = notApplicable == null ? List.of() : List.copyOf(notApplicable);
	}

	/**
	 * 便捷构造。
	 *
	 * @param scores 分数表
	 * @param notApplicable 不适用指标
	 * @param overall 综合均值
	 * @return 结果
	 */
	public static EvaluationResult of(Map<String, Double> scores, List<String> notApplicable,
			double overall) {
		return new EvaluationResult(scores, notApplicable, overall);
	}

	/**
	 * 读取某指标分数，不适用或缺失返回 NaN。
	 *
	 * @param metric 指标名
	 * @return 分数
	 */
	public double score(String metric) {
		Double value = this.scores.get(metric);
		return value == null ? Double.NaN : value;
	}

	/**
	 * 转为可读的多行摘要（日志/报告用）。
	 *
	 * @return 摘要文本
	 */
	public String summary() {
		StringBuilder sb = new StringBuilder();
		sb.append("overall=").append(Double.isNaN(this.overall) ? "N/A" : fmt(this.overall));
		for (Map.Entry<String, Double> e : new LinkedHashMap<>(this.scores).entrySet()) {
			sb.append('\n').append(e.getKey()).append('=').append(fmt(e.getValue()));
		}
		if (!this.notApplicable.isEmpty()) {
			sb.append("\nnotApplicable=").append(this.notApplicable);
		}
		return sb.toString();
	}

	private static String fmt(double v) {
		return String.format("%.3f", v);
	}
}
