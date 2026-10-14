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

import com.sure.ai.evals.metric.JudgeMode;
import com.sure.ai.evals.metric.LlmJudgeMetric;
import com.sure.ai.evals.metric.MetricResult;

/**
 * {@link LlmJudgeMetric} 测试（四种模式与容错）。
 */
public class LlmJudgeMetricTest {

	@Test
	public void testBinaryYesNo() {
		LlmJudgeMetric yes = LlmJudgeMetric.builder(new ScriptedChatClient("YES"), "judge").build();
		assertTrue(yes.evaluate(caseOf("ref"), successOutcome("实际"), context()).passedAt(1.0));
		LlmJudgeMetric no = LlmJudgeMetric.builder(new ScriptedChatClient("NO"), "judge").build();
		assertFalse(no.evaluate(caseOf("ref"), successOutcome("实际"), context()).passedAt(1.0));
	}

	@Test
	public void testChineseBinary() {
		LlmJudgeMetric m = LlmJudgeMetric.builder(new ScriptedChatClient("是，相关"), "j").build();
		assertTrue(m.evaluate(caseOf("r"), successOutcome("a"), context()).passedAt(1.0));
		LlmJudgeMetric m2 = LlmJudgeMetric.builder(new ScriptedChatClient("否，不相关"), "j").build();
		assertFalse(m2.evaluate(caseOf("r"), successOutcome("a"), context()).passedAt(1.0));
	}

	@Test
	public void testScale1To5() {
		LlmJudgeMetric m = LlmJudgeMetric.builder(new ScriptedChatClient("评分：4"), "j")
			.mode(JudgeMode.SCALE_1_TO_5).build();
		MetricResult r = m.evaluate(caseOf("r"), successOutcome("a"), context());
		assertEquals((4 - 1) / 4.0, r.score(), 1e-9);
	}

	@Test
	public void testScore100() {
		LlmJudgeMetric m = LlmJudgeMetric.builder(new ScriptedChatClient("85分"), "j")
			.mode(JudgeMode.SCORE_100).build();
		assertEquals(0.85, m.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 1e-9);
	}

	@Test
	public void testPairwise() {
		LlmJudgeMetric a = LlmJudgeMetric.builder(new ScriptedChatClient("A"), "j")
			.mode(JudgeMode.PAIRWISE).build();
		assertEquals(1.0, a.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 0.0);
		LlmJudgeMetric b = LlmJudgeMetric.builder(new ScriptedChatClient("B 更好"), "j")
			.mode(JudgeMode.PAIRWISE).build();
		assertEquals(0.0, b.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 0.0);
		LlmJudgeMetric tie = LlmJudgeMetric.builder(new ScriptedChatClient("TIE"), "j")
			.mode(JudgeMode.PAIRWISE).build();
		assertEquals(0.5, tie.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 1e-9);
	}

	@Test
	public void testModelExceptionIsNotApplicable() {
		ScriptedChatClient boom = new ScriptedChatClient().alwaysThrow(new RuntimeException("net"));
		LlmJudgeMetric m = LlmJudgeMetric.builder(boom, "j").build();
		MetricResult r = m.evaluate(caseOf("r"), successOutcome("a"), context());
		assertFalse(r.applicable());
	}

	@Test
	public void testUnparseableDefaultScore() {
		LlmJudgeMetric m = LlmJudgeMetric.builder(new ScriptedChatClient("完全无法判定"), "j")
			.onUnparseableDefaultScore(0.3).build();
		MetricResult r = m.evaluate(caseOf("r"), successOutcome("a"), context());
		assertTrue(r.applicable());
		assertEquals(0.3, r.score(), 0.0);
	}

	@Test
	public void testBlankReplyIsNotApplicable() {
		LlmJudgeMetric m = LlmJudgeMetric.builder(new ScriptedChatClient("   "), "j").build();
		assertFalse(m.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
	}

	@Test
	public void testConfigurableNameThresholdRubric() {
		ScriptedChatClient client = new ScriptedChatClient("YES");
		LlmJudgeMetric m = LlmJudgeMetric.builder(client, "judge-model")
			.name("my_judge").rubric("正确性优先").passThreshold(1.0).maxTokens(128).build();
		assertEquals("my_judge", m.name());
		assertEquals(1.0, m.passThreshold(), 0.0);
		assertTrue(m.evaluate(caseOf("r"), successOutcome("a"), context()).passedAt(1.0));
		assertTrue(client.prompts().get(0).contains("正确性优先"));
	}

	@Test
	public void testScaleOutOfRangeNotApplicable() {
		LlmJudgeMetric m = LlmJudgeMetric.builder(new ScriptedChatClient("8"), "j")
			.mode(JudgeMode.SCALE_1_TO_5).build();
		assertFalse(m.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
	}
}
