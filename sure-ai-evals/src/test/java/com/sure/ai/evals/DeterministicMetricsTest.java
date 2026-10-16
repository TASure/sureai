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

import static com.sure.ai.evals.OutcomeSupport.caseOf;
import static com.sure.ai.evals.OutcomeSupport.context;
import static com.sure.ai.evals.OutcomeSupport.successOutcome;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.evals.metric.ContainsMetric;
import com.sure.ai.evals.metric.ExactMatchMetric;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.metric.RegexMetric;
import com.sure.ai.model.TokenUsage;

/**
 * 确定性指标测试（精确 / 包含 / 正则 / 运行指标）。
 */
public class DeterministicMetricsTest {

	@Test
	public void testExactMatch() {
		ExactMatchMetric m = ExactMatchMetric.builder().build();
		MetricResult r = m.evaluate(caseOf("你好 世界"), successOutcome("你好  世界"), context());
		assertTrue(r.passedAt(1.0));
		MetricResult r2 = m.evaluate(caseOf("你好"), successOutcome("再见"), context());
		assertFalse(r2.passedAt(1.0));
		assertEquals("exact_match", m.name());
	}

	@Test
	public void testExactMatchIgnoreCase() {
		ExactMatchMetric m = ExactMatchMetric.builder().ignoreCase(true).build();
		MetricResult r = m.evaluate(caseOf("Hello"), successOutcome("hello"), context());
		assertTrue(r.passedAt(1.0));
	}

	@Test
	public void testExactMatchNullExpected() {
		ExactMatchMetric m = ExactMatchMetric.builder().build();
		MetricResult r = m.evaluate(caseOf(null), successOutcome("x"), context());
		assertFalse(r.applicable());
	}

	@Test
	public void testContainsAnyAndAll() {
		ContainsMetric any = ContainsMetric.builder().delimiter(";").build();
		MetricResult r = any.evaluate(caseOf("甲;乙"), successOutcome("内容有甲"), context());
		assertTrue(r.passedAt(1.0));
		ContainsMetric all = ContainsMetric.builder().delimiter(";").allRequired(true).build();
		MetricResult r2 = all.evaluate(caseOf("甲;乙"), successOutcome("内容有甲"), context());
		assertFalse(r2.passedAt(1.0));
		MetricResult r3 = all.evaluate(caseOf("甲;乙"), successOutcome("甲和乙都有"), context());
		assertTrue(r3.passedAt(1.0));
	}

	@Test
	public void testContainsIgnoreCase() {
		ContainsMetric m = ContainsMetric.builder().ignoreCase(true).build();
		MetricResult r = m.evaluate(caseOf("Error"), successOutcome("error occurred"), context());
		assertTrue(r.passedAt(1.0));
	}

	@Test
	public void testContainsNullExpected() {
		ContainsMetric m = ContainsMetric.builder().build();
		MetricResult r = m.evaluate(caseOf(null), successOutcome("x"), context());
		assertFalse(r.applicable());
	}

	@Test
	public void testRegexFindAndMatches() {
		RegexMetric find = RegexMetric.builder().build();
		assertTrue(find.evaluate(caseOf("\\d+"), successOutcome("订单 123 完成"), context()).passedAt(1.0));
		RegexMetric matches = RegexMetric.builder().mode(RegexMetric.Mode.MATCHES).build();
		assertFalse(matches.evaluate(caseOf("\\d+"), successOutcome("订单 123"), context()).passedAt(1.0));
	}

	@Test
	public void testRegexInvalidAndNull() {
		RegexMetric m = RegexMetric.builder().build();
		MetricResult bad = m.evaluate(caseOf("([unclosed"), successOutcome("x"), context());
		assertEquals(0.0, bad.score(), 0.0);
		assertFalse(m.evaluate(caseOf(null), successOutcome("x"), context()).applicable());
	}

	@Test
	public void testLatencyMetric() {
		com.sure.ai.evals.metric.LatencyMetric m = com.sure.ai.evals.metric.LatencyMetric.builder()
			.targetMs(100).limitMs(300).build();
		assertEquals(1.0, m.evaluate(caseOf(null), successOutcome("x", null, 50_000_000L), context()).score(), 0.0);
		assertEquals(0.0, m.evaluate(caseOf(null), successOutcome("x", null, 500_000_000L), context()).score(), 0.0);
		double mid = m.evaluate(caseOf(null), successOutcome("x", null, 200_000_000L), context()).score();
		assertEquals(0.5, mid, 1e-9);
		assertEquals("latency", m.name());
	}

	@Test
	public void testTokenCostMetric() {
		com.sure.ai.evals.metric.TokenCostMetric m = com.sure.ai.evals.metric.TokenCostMetric.builder()
			.targetTokens(100).limitTokens(300).build();
		assertEquals(1.0, m.evaluate(caseOf(null),
			successOutcome("x", TokenUsage.of(10, 20, 30), 0L), context()).score(), 0.0);
		assertEquals(0.0, m.evaluate(caseOf(null),
			successOutcome("x", TokenUsage.of(100, 200, 300), 0L), context()).score(), 0.0);
		MetricResult na = m.evaluate(caseOf(null), successOutcome("x", null, 0L), context());
		assertFalse(na.applicable());
		assertEquals("token_cost", m.name());
	}
}
