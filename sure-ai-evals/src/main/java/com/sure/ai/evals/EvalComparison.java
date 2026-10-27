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
package com.sure.ai.evals;

import java.util.LinkedHashMap;
import java.util.Map;

import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;
import com.sure.tool.lang.Assert;

/**
 * A/B 报告对比：两份 {@link EvalReport}（两个 runner / 模型）逐指标对比。
 *
 * <p>输出两类结果：</p>
 * <ul>
 *   <li>每指标均分差值（A − B）：{@link #meanDiffs()}；</li>
 *   <li>逐用例逐指标 pairwise 胜负：对两报告共有的用例与指标，比较同一用例上 A、B 的
 *       分数（NaN 跳过），{@link #winsA()} / {@link #ties()} / {@link #winsB()}。</li>
 * </ul>
 *
 * <p>结果不可变。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalComparison {

	private final EvalReport reportA;
	private final EvalReport reportB;
	private final Map<String, Double> meanDiffs;
	private final int winsA;
	private final int ties;
	private final int winsB;

	private EvalComparison(Builder b) {
		this.reportA = b.reportA;
		this.reportB = b.reportB;
		this.meanDiffs = Map.copyOf(b.meanDiffs);
		this.winsA = b.winsA;
		this.ties = b.ties;
		this.winsB = b.winsB;
	}

	/**
	 * 对比两份报告。
	 *
	 * @param reportA 报告 A（基准）
	 * @param reportB 报告 B（对照）
	 * @return 对比结果
	 */
	public static EvalComparison of(EvalReport reportA, EvalReport reportB) {
		Assert.notNull(reportA, "reportA 不能为 null");
		Assert.notNull(reportB, "reportB 不能为 null");
		Builder builder = new Builder().reportA(reportA).reportB(reportB);
		for (Map.Entry<String, MetricStats> e : reportA.metricStats().entrySet()) {
			String metric = e.getKey();
			MetricStats statsB = reportB.metricStats(metric);
			if (statsB == null) {
				continue;
			}
			double meanA = e.getValue().mean();
			double meanB = statsB.mean();
			builder.meanDiff(metric, Double.isNaN(meanA) || Double.isNaN(meanB)
				? Double.NaN : meanA - meanB);
		}
		int winsA = 0;
		int ties = 0;
		int winsB = 0;
		for (CaseDetail detailA : reportA.details()) {
			CaseDetail detailB = reportB.detailByCaseId(detailA.caseId());
			if (detailB == null) {
				continue;
			}
			for (Map.Entry<String, MetricResult> e : detailA.metrics().entrySet()) {
				MetricResult resultB = detailB.metrics().get(e.getKey());
				if (resultB == null || !e.getValue().applicable() || !resultB.applicable()) {
					continue;
				}
				double diff = e.getValue().score() - resultB.score();
				if (diff > 0.0) {
					winsA++;
				} else if (diff < 0.0) {
					winsB++;
				} else {
					ties++;
				}
			}
		}
		return builder.wins(winsA, ties, winsB).build();
	}

	/**
	 * 报告 A。
	 *
	 * @return 报告
	 */
	public EvalReport reportA() {
		return this.reportA;
	}

	/**
	 * 报告 B。
	 *
	 * @return 报告
	 */
	public EvalReport reportB() {
		return this.reportB;
	}

	/**
	 * 每指标均分差值（A − B，NaN 表示某侧不适用）。
	 *
	 * @return 不可变 Map
	 */
	public Map<String, Double> meanDiffs() {
		return this.meanDiffs;
	}

	/**
	 * pairwise 中 A 获胜的次数。
	 *
	 * @return 次数
	 */
	public int winsA() {
		return this.winsA;
	}

	/**
	 * pairwise 平局次数。
	 *
	 * @return 次数
	 */
	public int ties() {
		return this.ties;
	}

	/**
	 * pairwise 中 B 获胜的次数。
	 *
	 * @return 次数
	 */
	public int winsB() {
		return this.winsB;
	}

	/**
	 * Builder。
	 */
	static final class Builder {

		private EvalReport reportA;
		private EvalReport reportB;
		private final Map<String, Double> meanDiffs = new LinkedHashMap<>();
		private int winsA;
		private int ties;
		private int winsB;

		private Builder() {
		}

		Builder reportA(EvalReport reportA) {
			this.reportA = reportA;
			return this;
		}

		Builder reportB(EvalReport reportB) {
			this.reportB = reportB;
			return this;
		}

		Builder meanDiff(String metric, double diff) {
			this.meanDiffs.put(metric, diff);
			return this;
		}

		Builder wins(int winsA, int ties, int winsB) {
			this.winsA = winsA;
			this.ties = ties;
			this.winsB = winsB;
			return this;
		}

		EvalComparison build() {
			return new EvalComparison(this);
		}
	}
}
