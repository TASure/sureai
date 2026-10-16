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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.evals.EvalUtil;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

/**
 * {@link TraceRegression} 判定算法、容差边界与 CI 断言。
 */
public class TraceRegressionTest {

	private static MetricStats stats(String name, double mean) {
		return new MetricStats(name, 3, 0, mean, mean, mean, 3, 1.0);
	}

	private static EvalReport report(String name, Map<String, MetricStats> stats,
			List<CaseDetail> details, long failed) {
		return EvalReport.builder()
			.datasetName(name)
			.model("m")
			.details(details)
			.statsByMetric(stats)
			.tokens(6, 9, 15)
			.failedCases(failed)
			.build();
	}

	private static CaseDetail caseDetail(String caseId, Map<String, MetricResult> metrics) {
		return caseDetail(caseId, true, metrics);
	}

	private static CaseDetail caseDetail(String caseId, boolean succeeded,
			Map<String, MetricResult> metrics) {
		return new CaseDetail(caseId, List.of(), succeeded, succeeded ? null : "boom",
			100L, 2, 3, 5, "text", metrics);
	}

	private static Map<String, MetricResult> scores(double... s) {
		Map<String, MetricResult> map = new LinkedHashMap<>();
		for (int i = 0; i < s.length; i++) {
			map.put("acc" + (i + 1), MetricResult.of(s[i], "r"));
		}
		return map;
	}

	@Test
	public void 完全一致无退化() {
		Map<String, MetricStats> stats = new LinkedHashMap<>();
		stats.put("acc", stats("acc", 0.9));
		EvalReport baseline = report("ds", stats, List.of(caseDetail("c1", scores(1.0))), 0);
		EvalReport current = report("ds", stats, List.of(caseDetail("c1", scores(1.0))), 0);
		TraceRegression.RegressionResult result = TraceRegression.compare(baseline, current);
		assertTrue(result.passed());
		assertTrue(result.degradations().isEmpty());
		assertTrue(result.summary().contains("通过"));
		EvalUtil.assertNoRegression(baseline, current);
	}

	@Test
	public void 均值退化超容差判退化() {
		Map<String, MetricStats> b = new LinkedHashMap<>();
		b.put("acc", stats("acc", 0.90));
		Map<String, MetricStats> c = new LinkedHashMap<>();
		c.put("acc", stats("acc", 0.87));
		EvalReport baseline = report("ds", b, List.of(), 0);
		EvalReport current = report("ds", c, List.of(), 0);
		TraceRegression.RegressionResult result = TraceRegression.compare(baseline, current);
		assertFalse(result.passed());
		assertEquals(1, result.degradations().size());
		assertTrue(result.summary().contains("acc"));
		AssertionError err = assertThrows(AssertionError.class, result::assertPassed);
		assertTrue(err.getMessage().contains("acc"));
	}

	@Test
	public void 退化幅度恰好等于容差不判退化() {
		// 取二进制精确值：delta = 0.5 - 0.4375 = 0.0625 == tolerance，严格大于才判退化
		Map<String, MetricStats> b = new LinkedHashMap<>();
		b.put("acc", stats("acc", 0.5));
		Map<String, MetricStats> c = new LinkedHashMap<>();
		c.put("acc", stats("acc", 0.4375));
		EvalReport baseline = report("ds", b, List.of(), 0);
		EvalReport current = report("ds", c, List.of(), 0);
		TraceRegression.RegressionResult result = TraceRegression.builder()
			.tolerance(0.0625).build()
			.compareReports(baseline, current);
		assertTrue(result.passed());
		// 同幅度略超即退化
		Map<String, MetricStats> c2 = new LinkedHashMap<>();
		c2.put("acc", stats("acc", 0.43));
		assertFalse(TraceRegression.builder().tolerance(0.0625).build()
			.compareReports(baseline, report("ds", c2, List.of(), 0)).passed());
	}

	@Test
	public void 改善记信息项不失败() {
		Map<String, MetricStats> b = new LinkedHashMap<>();
		b.put("acc", stats("acc", 0.80));
		Map<String, MetricStats> c = new LinkedHashMap<>();
		c.put("acc", stats("acc", 0.95));
		EvalReport baseline = report("ds", b, List.of(), 0);
		EvalReport current = report("ds", c, List.of(), 0);
		TraceRegression.RegressionResult result = TraceRegression.compare(baseline, current);
		assertTrue(result.passed());
		assertTrue(result.degradations().isEmpty());
	}

	@Test
	public void 当前缺失基线指标判退化新增指标只记信息() {
		Map<String, MetricStats> b = new LinkedHashMap<>();
		b.put("old", stats("old", 0.9));
		Map<String, MetricStats> c = new LinkedHashMap<>();
		c.put("new", stats("new", 0.9));
		EvalReport baseline = report("ds", b, List.of(), 0);
		EvalReport current = report("ds", c, List.of(), 0);
		TraceRegression.RegressionResult result = TraceRegression.compare(baseline, current);
		assertFalse(result.passed());
		assertEquals(1, result.degradations().size());
		assertEquals("old", result.degradations().get(0).metric());
	}

	@Test
	public void 失败用例数增加判退化减少记改善() {
		Map<String, MetricStats> stats = new LinkedHashMap<>();
		stats.put("acc", stats("acc", 0.9));
		EvalReport baseline = report("ds", stats, List.of(), 1);
		EvalReport current = report("ds", stats, List.of(), 3);
		TraceRegression.RegressionResult down = TraceRegression.compare(baseline, current);
		assertFalse(down.passed());
		assertTrue(down.degradations().get(0).metric().contains("failed-cases"));

		EvalReport repaired = report("ds", stats, List.of(), 0);
		assertTrue(TraceRegression.compare(baseline, repaired).passed());
	}

	@Test
	public void 逐用例分数跌幅超容差判退化() {
		Map<String, MetricStats> stats = new LinkedHashMap<>();
		stats.put("acc", stats("acc", 0.5));
		EvalReport baseline = report("ds", stats, List.of(caseDetail("c1", scores(1.0))), 0);
		EvalReport current = report("ds", stats, List.of(caseDetail("c1", scores(0.0))), 0);
		TraceRegression.RegressionResult result = TraceRegression.compare(baseline, current);
		assertFalse(result.passed());
		assertTrue(result.degradations().stream().anyMatch(d -> d.metric().startsWith("case:c1")));
	}

	@Test
	public void 用例在当前报告丢失判退化() {
		Map<String, MetricStats> stats = new LinkedHashMap<>();
		stats.put("acc", stats("acc", 0.9));
		EvalReport baseline = report("ds", stats, List.of(caseDetail("c1", scores(1.0))), 0);
		EvalReport current = report("ds", stats, List.of(), 0);
		assertFalse(TraceRegression.compare(baseline, current).passed());
	}

	@Test
	public void 按指标自定义容差生效() {
		Map<String, MetricStats> b = new LinkedHashMap<>();
		b.put("strict", stats("strict", 0.90));
		Map<String, MetricStats> c = new LinkedHashMap<>();
		c.put("strict", stats("strict", 0.85));
		EvalReport baseline = report("ds", b, List.of(), 0);
		EvalReport current = report("ds", c, List.of(), 0);
		// 默认 0.02：delta 0.05 退化
		assertFalse(TraceRegression.compare(baseline, current).passed());
		// 自定义 0.10：不退化
		TraceRegression.RegressionResult result = TraceRegression.builder()
			.tolerance("strict", 0.10).build()
			.compareReports(baseline, current);
		assertTrue(result.passed());
	}

	@Test
	public void 用例成功变失败与当前缺失指标判退化() {
		Map<String, MetricStats> stats = new LinkedHashMap<>();
		stats.put("acc", stats("acc", 0.9));
		// 基线用例成功，当前用例失败
		EvalReport baseline = report("ds", stats, List.of(caseDetail("c1", scores(1.0))), 0);
		EvalReport current = report("ds", stats, List.of(caseDetail("c1", false, scores(1.0))), 1);
		TraceRegression.RegressionResult r1 = TraceRegression.compare(baseline, current);
		assertFalse(r1.passed());
		assertTrue(r1.degradations().stream().anyMatch(d -> d.metric().startsWith("case:c1")));

		// 基线指标适用，当前用例该指标缺失
		EvalReport repaired = report("ds", stats,
			List.of(caseDetail("c1", new LinkedHashMap<>())), 0);
		TraceRegression.RegressionResult r2 = TraceRegression.compare(baseline, repaired);
		assertFalse(r2.passed());
		assertTrue(r2.degradations().stream().anyMatch(d -> d.metric().equals("case:c1/acc1")));
	}

	@Test
	public void 参数校验与NaN均值跳过() {
		assertThrows(RuntimeException.class, () -> TraceRegression.compare(null, null));
		Map<String, MetricStats> b = new LinkedHashMap<>();
		b.put("acc", new MetricStats("acc", 0, 3, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN));
		EvalReport baseline = report("ds", b, List.of(), 0);
		EvalReport current = report("ds", new LinkedHashMap<>(), List.of(), 0);
		assertTrue(TraceRegression.compare(baseline, current).passed());
	}
}
