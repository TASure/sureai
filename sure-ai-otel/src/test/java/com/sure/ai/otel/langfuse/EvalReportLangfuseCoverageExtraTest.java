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

package com.sure.ai.otel.langfuse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

import org.junit.Test;

/**
 * {@link EvalReportLangfuseExporter} 防御分支覆盖补齐：startedAt=0/数据集 null、
 * 全 NaN 指标（无 pass_rate）、comment 的 NaN passRate、json(null) 与转义分支。零真实网络。
 *
 * @author sureai
 * @since 2.8.0
 */
public class EvalReportLangfuseCoverageExtraTest {

	/** 录制发送器：收集所有信封 JSON。 */
	private static List<String> record(EvalReport report, boolean enabled, String env) {
		List<String> out = new ArrayList<>();
		LangfuseBatchSender recorder = events -> {
			out.addAll(events);
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		};
		new EvalReportLangfuseExporter(enabled, recorder, env).export(report).join();
		return out;
	}

	private static EvalReport baseReport(Map<String, MetricStats> stats, String dataset) {
		return EvalReport.builder()
			.datasetName(dataset)
			.model("m")
			.details(List.of(new CaseDetail("c1", List.of(), true, null, 1L, 1, 1, 2, "a",
				Map.of())))
			.statsByMetric(stats)
			.tokens(10, 5, 15)
			.failedCases(0)
			.build();
	}

	/** startedAt=0、dataset=null：回退 "dataset"，trace id 以 eval-dataset-0 开头。 */
	@Test
	public void zeroStartAndNullDatasetFallback() {
		EvalReport report = EvalReport.builder()
			.datasetName(null)
			.model("m")
			.startedAtMillis(0)
			.statsByMetric(Map.of("exact_match",
				new MetricStats("exact_match", 1, 0, 1.0, 1.0, 1.0, 1, 1.0)))
			.build();
		List<String> batch = record(report, true, null);
		String trace = batch.get(0);
		assertTrue(trace.contains("\"id\":\"eval-dataset-0\""));
		assertTrue(trace.contains("\"name\":\"eval:dataset\""));
	}

	/** 全部指标 NaN（无适用用例）：不产出 pass_rate score。 */
	@Test
	public void allNanMetricsNoPassRate() {
		Map<String, MetricStats> stats = Map.of("nan_metric",
			new MetricStats("nan_metric", 0, 3, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN));
		List<String> batch = record(baseReport(stats, "ds"), true, null);
		boolean hasPassRate = false;
		for (String e : batch) {
			if (e.contains("\"name\":\"pass_rate\"")) {
				hasPassRate = true;
			}
		}
		assertFalse(hasPassRate);
	}

	/** 指标均值非 NaN 但 passRate 为 NaN：comment 走 n/a 分支。 */
	@Test
	public void commentNanPassRateBranch() {
		// mean=0.8（非 NaN，会产出 score），passRate=NaN
		Map<String, MetricStats> stats = Map.of("weird",
			new MetricStats("weird", 5, 0, 0.8, 0.0, 1.0, 0, Double.NaN));
		List<String> batch = record(baseReport(stats, "ds"), true, null);
		boolean found = false;
		for (String e : batch) {
			if (e.contains("\"name\":\"weird\"") && e.contains("n/a")) {
				found = true;
			}
		}
		assertTrue(found);
	}

	/** json(null) → 空串字面量。 */
	@Test
	public void jsonNullLiteral() {
		assertEquals("\"\"", EvalReportLangfuseExporter.json(null));
	}

	/** 数据集名含引号/反斜杠/换行/回车/制表符/控制符：JSON 转义正确，信封不被破坏。 */
	@Test
	public void jsonEscapingThroughExport() {
		String tricky = "a\"b\\c\nd\te\rf\u0001g";
		EvalReport report = baseReport(Map.of("exact_match",
			new MetricStats("exact_match", 1, 0, 1.0, 1.0, 1.0, 1, 1.0)), tricky);
		List<String> batch = record(report, true, "prod");
		String trace = batch.get(0);
		assertTrue(trace.contains("\\\""));
		assertTrue(trace.contains("\\\\"));
		assertTrue(trace.contains("\\n"));
		assertTrue(trace.contains("\\t"));
		assertTrue(trace.contains("\\r"));
		assertTrue(trace.contains("\\u0001"));
		// environment 也被写入
		assertTrue(trace.contains("\"environment\":\"prod\""));
	}
}
