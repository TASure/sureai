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
package com.sure.ai.evals.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.tool.lang.Assert;

/**
 * 指标回归比对：基线报告 vs 当前报告，判定「当前相对基线是否出现不可接受退化」。
 *
 * <p>判定算法（均值口径，分数越高越好）：</p>
 * <ul>
 *   <li>按指标聚合均值 {@code mean} 比较：{@code delta = baseline - current}；
 *       当 {@code delta > tolerance} 判退化（{@code delta == tolerance} 恰好相等
 *       <b>不</b>判退化），{@code delta < -tolerance} 记为改善（信息项，不失败）。</li>
 *   <li>容差：默认 {@value #DEFAULT_TOLERANCE}，可全局调整或按指标名单独配置。</li>
 *   <li>对齐：指标取两报告并集；用例按 {@code caseId} 对齐。基线有、当前缺失（或
 *       均值为 NaN）判退化（指标被摘掉/用例丢失）；基线缺失、当前新增记为信息项。</li>
 *   <li>逐用例：同 caseId 下逐指标分数跌幅超容差同样记退化；失败用例数增加记退化。</li>
 * </ul>
 *
 * <p>用于 golden dataset 工作流：录制当次报告作基线，回放重跑报告作当前——
 * 理想回放应「零退化」。对接 CI：{@link RegressionResult#assertPassed()} 遇退化抛
 * {@link AssertionError}，消息含全部明细。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class TraceRegression {

	/** 默认每指标允许退化容差。 */
	public static final double DEFAULT_TOLERANCE = 0.02;

	private static final String FAILED_CASES_METRIC = "<failed-cases>";
	private static final double FAILED_CASES_TOLERANCE = 0.0;

	private final double defaultTolerance;
	private final Map<String, Double> tolerances;

	private TraceRegression(Builder b) {
		this.defaultTolerance = b.defaultTolerance;
		this.tolerances = Map.copyOf(b.tolerances);
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 以默认容差（0.02）比对两份报告。
	 *
	 * @param baseline 基线报告
	 * @param current  当前报告
	 * @return 比对结果
	 */
	public static RegressionResult compare(EvalReport baseline, EvalReport current) {
		return builder().build().compareReports(baseline, current);
	}

	/**
	 * 比对两份报告。
	 *
	 * @param baseline 基线报告
	 * @param current  当前报告
	 * @return 比对结果
	 */
	public RegressionResult compareReports(EvalReport baseline, EvalReport current) {
		Assert.notNull(baseline, "baseline 不能为 null");
		Assert.notNull(current, "current 不能为 null");
		List<RegressionDetail> details = new ArrayList<>();
		compareFailedCases(baseline, current, details);
		compareMetricMeans(baseline, current, details);
		compareCaseLevel(baseline, current, details);
		boolean passed = details.stream().noneMatch(d -> d.level() == Level.DEGRADED);
		return new RegressionResult(passed, List.copyOf(details),
			baseline.datasetName(), current.datasetName());
	}

	private void compareFailedCases(EvalReport baseline, EvalReport current,
			List<RegressionDetail> details) {
		long delta = current.failedCaseCount() - baseline.failedCaseCount();
		if (delta > 0) {
			details.add(new RegressionDetail(FAILED_CASES_METRIC, Level.DEGRADED,
				(double) baseline.failedCaseCount(), (double) current.failedCaseCount(),
				delta, FAILED_CASES_TOLERANCE,
				"失败用例数增加 " + baseline.failedCaseCount() + " → " + current.failedCaseCount()));
		} else if (delta < 0) {
			details.add(new RegressionDetail(FAILED_CASES_METRIC, Level.IMPROVED,
				(double) baseline.failedCaseCount(), (double) current.failedCaseCount(),
				delta, FAILED_CASES_TOLERANCE,
				"失败用例数减少 " + baseline.failedCaseCount() + " → " + current.failedCaseCount()));
		}
	}

	private void compareMetricMeans(EvalReport baseline, EvalReport current,
			List<RegressionDetail> details) {
		Map<String, MetricStats> baselineStats = baseline.metricStats();
		Map<String, MetricStats> currentStats = current.metricStats();
		Map<String, Boolean> union = new LinkedHashMap<>();
		for (String name : baselineStats.keySet()) {
			union.put(name, Boolean.TRUE);
		}
		for (String name : currentStats.keySet()) {
			union.putIfAbsent(name, Boolean.FALSE);
		}
		for (String name : union.keySet()) {
			double tolerance = toleranceOf(name);
			MetricStats b = baselineStats.get(name);
			MetricStats c = currentStats.get(name);
			double meanB = b == null ? Double.NaN : b.mean();
			double meanC = c == null ? Double.NaN : c.mean();
			if (Double.isNaN(meanB) && Double.isNaN(meanC)) {
				continue;
			}
			if (!Double.isNaN(meanB) && Double.isNaN(meanC)) {
				details.add(new RegressionDetail(name, Level.DEGRADED, meanB, meanC,
					Double.NaN, tolerance, "基线有均值而当前报告缺失/全不适用"));
				continue;
			}
			if (Double.isNaN(meanB)) {
				details.add(new RegressionDetail(name, Level.IMPROVED, meanB, meanC,
					Double.NaN, tolerance, "当前新增指标，无基线可比"));
				continue;
			}
			double delta = meanB - meanC;
			if (delta > tolerance) {
				details.add(new RegressionDetail(name, Level.DEGRADED, meanB, meanC,
					delta, tolerance, "均值退化超容差"));
			} else if (delta < -tolerance) {
				details.add(new RegressionDetail(name, Level.IMPROVED, meanB, meanC,
					delta, tolerance, "均值改善"));
			} else {
				details.add(new RegressionDetail(name, Level.OK, meanB, meanC,
					delta, tolerance, "均值在容差内"));
			}
		}
	}

	private void compareCaseLevel(EvalReport baseline, EvalReport current,
			List<RegressionDetail> details) {
		for (CaseDetail bDetail : baseline.details()) {
			CaseDetail cDetail = current.detailByCaseId(bDetail.caseId());
			if (cDetail == null) {
				details.add(new RegressionDetail("case:" + bDetail.caseId(), Level.DEGRADED,
					1.0, 0.0, 1.0, 0.0, "基线用例在当前报告缺失"));
				continue;
			}
			if (bDetail.succeeded() && !cDetail.succeeded()) {
				details.add(new RegressionDetail("case:" + bDetail.caseId(), Level.DEGRADED,
					1.0, 0.0, 1.0, 0.0, "用例由成功变失败"));
			}
			for (Map.Entry<String, MetricResult> entry : bDetail.metrics().entrySet()) {
				String name = entry.getKey();
				MetricResult rb = entry.getValue();
				if (!rb.applicable()) {
					continue;
				}
				MetricResult rc = cDetail.metrics().get(name);
				double tolerance = toleranceOf(name);
				if (rc == null || !rc.applicable()) {
					details.add(new RegressionDetail("case:" + bDetail.caseId() + "/" + name,
						Level.DEGRADED, rb.score(), Double.NaN, Double.NaN, tolerance,
						"基线适用而当前不适用/缺失"));
					continue;
				}
				double delta = rb.score() - rc.score();
				if (delta > tolerance) {
					details.add(new RegressionDetail("case:" + bDetail.caseId() + "/" + name,
						Level.DEGRADED, rb.score(), rc.score(), delta, tolerance,
						"逐用例分数退化超容差"));
				}
			}
		}
	}

	private double toleranceOf(String metric) {
		Double t = this.tolerances.get(metric);
		return t == null ? this.defaultTolerance : t;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private double defaultTolerance = DEFAULT_TOLERANCE;
		private final Map<String, Double> tolerances = new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * 全局默认容差。
		 *
		 * @param tolerance 容差（≥0）
		 * @return this
		 */
		public Builder tolerance(double tolerance) {
			Assert.isTrue(tolerance >= 0.0, "tolerance 不能为负");
			this.defaultTolerance = tolerance;
			return this;
		}

		/**
		 * 按指标名单独配置容差。
		 *
		 * @param metric    指标名
		 * @param tolerance 容差
		 * @return this
		 */
		public Builder tolerance(String metric, double tolerance) {
			Assert.notNull(metric, "metric 不能为 null");
			Assert.isTrue(tolerance >= 0.0, "tolerance 不能为负");
			this.tolerances.put(metric, tolerance);
			return this;
		}

		/**
		 * 构建比对器。
		 *
		 * @return 比对器
		 */
		public TraceRegression build() {
			return new TraceRegression(this);
		}
	}

	/**
	 * 比对级别。
	 */
	public enum Level {

		/** 退化（导致 {@code passed=false}）。 */
		DEGRADED,

		/** 改善（信息项）。 */
		IMPROVED,

		/** 容差内持平（信息项）。 */
		OK
	}

	/**
	 * 单项退化明细（不可变）。
	 *
	 * @param metric    指标名（case 级为 {@code case:<id>/<指标名}，失败用例数为占位名）
	 * @param level     级别
	 * @param baseline  基线值（缺失为 null）
	 * @param current   当前值（缺失为 null）
	 * @param delta     退化幅度（baseline - current），不可比时 NaN
	 * @param tolerance 适用容差
	 * @param message   可读说明
	 * @author sureai
	 * @since 2.8.0
	 */
	public record RegressionDetail(String metric, Level level, Double baseline, Double current,
			double delta, double tolerance, String message) {
	}

	/**
	 * 回归比对结果（不可变）。
	 *
	 * @param passed       是否无退化
	 * @param details      全部比对明细
	 * @param baselineName 基线数据集名
	 * @param currentName  当前数据集名
	 * @author sureai
	 * @since 2.8.0
	 */
	public record RegressionResult(boolean passed, List<RegressionDetail> details,
			String baselineName, String currentName) {

		/**
		 * 紧凑构造器。
		 */
		public RegressionResult {
			details = List.copyOf(details);
		}

		/**
		 * 退化明细列表（仅 DEGRADED）。
		 *
		 * @return 退化明细
		 */
		public List<RegressionDetail> degradations() {
			List<RegressionDetail> out = new ArrayList<>();
			for (RegressionDetail d : this.details) {
				if (d.level() == Level.DEGRADED) {
					out.add(d);
				}
			}
			return List.copyOf(out);
		}

		/**
		 * 可读摘要（CI 日志友好）。
		 *
		 * @return 多行摘要
		 */
		public String summary() {
			StringBuilder sb = new StringBuilder();
			sb.append("回归比对[").append(this.baselineName).append(" → ").append(this.currentName)
				.append("]: ").append(this.passed ? "通过" : "存在退化")
				.append("（明细 ").append(this.details.size()).append(" 条，退化 ")
				.append(degradations().size()).append(" 条）");
			for (RegressionDetail d : this.degradations()) {
				sb.append('\n').append("  [DEGRADED] ").append(d.metric())
					.append(" baseline=").append(fmt(d.baseline()))
					.append(" current=").append(fmt(d.current()))
					.append(" delta=").append(fmt(d.delta()))
					.append(" tol=").append(d.tolerance())
					.append(" — ").append(d.message());
			}
			return sb.toString();
		}

		/**
		 * 断言无退化：存在退化时抛 {@link AssertionError}（消息含全部退化明细）。
		 *
		 * @throws AssertionError 存在退化时抛出
		 */
		public void assertPassed() {
			if (!this.passed) {
				throw new AssertionError(this.summary());
			}
		}

		private static String fmt(Double v) {
			return v == null ? "null" : fmt(v.doubleValue());
		}

		private static String fmt(double v) {
			return Double.isNaN(v) ? "NaN" : String.format("%.4f", v);
		}
	}
}
