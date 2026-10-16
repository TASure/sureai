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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.evals.assertion.EvalAssertions;
import com.sure.ai.evals.assertion.EvalThreshold;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.EvalReport;

/**
 * 阈值断言、EvalUtil、A/B 对比测试。
 */
public class EvalAndUtilTest {

	private static EvalReport report(double score) {
		EvalMetric metric = new EvalMetric() {
			@Override
			public String name() {
				return "exact_match";
			}

			@Override
			public MetricResult evaluate(com.sure.ai.evals.dataset.EvalCase c,
					com.sure.ai.evals.metric.EvalOutcome o,
					com.sure.ai.evals.metric.EvalContext ctx) {
				return MetricResult.of(score, "fixed");
			}
		};
		ScriptedChatClient client = new ScriptedChatClient("a", "b");
		try (EvalRunner runner = EvalRunner.builder(client, "m").addMetric(metric).build()) {
			return runner.run(EvalDataset.of("d", List.of(
				EvalCase.of("c1", "q1", "r"), EvalCase.of("c2", "q2", "r"))));
		}
	}

	@Test
	public void testAssertMeetsPass() {
		EvalAssertions.assertMeets(report(0.9), EvalThreshold.of("exact_match", 0.8));
	}

	@Test
	public void testAssertMeetsFail() {
		AssertionError e = assertThrows(AssertionError.class,
			() -> EvalAssertions.assertMeets(report(0.5), EvalThreshold.of("exact_match", 0.8)));
		assertTrue(e.getMessage().contains("exact_match"));
	}

	@Test
	public void testMissingMetricFails() {
		AssertionError e = assertThrows(AssertionError.class,
			() -> EvalAssertions.assertMeets(report(1.0), EvalThreshold.of("missing", 0.5)));
		assertTrue(e.getMessage().contains("缺失"));
	}

	@Test
	public void testPassRateThreshold() {
		EvalThreshold t = EvalThreshold.builder().putPassRate("exact_match", 0.9).build();
		EvalAssertions.assertMeets(report(1.0), t);
		AssertionError e = assertThrows(AssertionError.class,
			() -> EvalAssertions.assertMeets(report(0.5), t));
		assertTrue(e.getMessage().contains("通过率"));
	}

	@Test
	public void testUtilQuickRun() {
		ScriptedChatClient client = new ScriptedChatClient("hello");
		EvalReport report = EvalUtil.run(client, "m",
			EvalUtil.dataset("d", EvalCase.of("c1", "q", "hello")),
			ExactMatchLike.METRIC);
		assertEquals(1.0, report.metricStats("custom").mean(), 0.0);
		EvalUtil.assertMeets(report, EvalThreshold.of("custom", 0.9));
	}

	@Test
	public void testUtilLoadersAndHolder() {
		assertEquals("csv-dataset", EvalUtil.csvLoader().load(
			new java.io.StringReader("input,expected\nq,a\n")).name());
		assertEquals("jsonl-dataset", EvalUtil.jsonlLoader().load(
			new java.io.StringReader("{\"input\":\"q\"}\n")).name());
		EvalUtil.initDefaultRunner(EvalRunner.builder(new ScriptedChatClient("x"), "m").build());
		assertTrue(EvalUtil.defaultRunner() != null);
		EvalUtil.clearDefaultRunner();
	}

	@Test
	public void testComparison() {
		EvalReport a = report(1.0);
		EvalReport b = report(0.5);
		EvalComparison cmp = EvalComparison.of(a, b);
		assertEquals(0.5, cmp.meanDiffs().get("exact_match"), 1e-9);
		assertEquals(2, cmp.winsA());
		assertEquals(0, cmp.winsB());
		assertEquals(0, cmp.ties());
		assertEquals("d", cmp.reportA().datasetName());
	}

	static final class ExactMatchLike {
		static final EvalMetric METRIC = new EvalMetric() {
			@Override
			public String name() {
				return "custom";
			}

			@Override
			public MetricResult evaluate(com.sure.ai.evals.dataset.EvalCase c,
					com.sure.ai.evals.metric.EvalOutcome o,
					com.sure.ai.evals.metric.EvalContext ctx) {
				return MetricResult.pass("ok");
			}
		};
	}
}
