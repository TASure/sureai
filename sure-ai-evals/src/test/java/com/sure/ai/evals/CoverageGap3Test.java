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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;

import org.junit.Test;

import com.sure.ai.evals.dataset.CsvDatasetLoader;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.dataset.JsonlDatasetLoader;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.JsonSchemaValidityMetric;
import com.sure.ai.evals.metric.LlmJudgeMetric;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.model.ChatMessage;

/**
 * 第三轮覆盖率补齐。
 */
public class CoverageGap3Test {

	record Rich(java.util.List<Integer> list, java.util.Map<String, String> obj, boolean flag) {
	}

	@Test
	public void testSchemaTypeMismatches() {
		JsonSchemaValidityMetric m = JsonSchemaValidityMetric.builder(Rich.class).build();
		// 数组/对象/布尔类型不匹配 + null 值
		assertFalse(m.evaluate(caseOf(null), successOutcome("{\"list\":1}"), context()).passedAt(1.0));
		assertFalse(m.evaluate(caseOf(null), successOutcome("{\"obj\":1}"), context()).passedAt(1.0));
		assertFalse(m.evaluate(caseOf(null), successOutcome("{\"flag\":\"yes\"}"), context()).passedAt(1.0));
		assertFalse(m.evaluate(caseOf(null), successOutcome("{\"list\":[1],\"obj\":{},\"flag\":null}"),
			context()).passedAt(1.0));
		// 全合法
		assertTrue(m.evaluate(caseOf(null), successOutcome("{\"list\":[1],\"obj\":{},\"flag\":true}"),
			context()).passedAt(1.0));
	}

	@Test
	public void testJudgeNullResponse() {
		LlmJudgeMetric m = LlmJudgeMetric.builder(new ScriptedChatClient("x") {
			@Override
			public com.sure.ai.model.ChatResponse chat(com.sure.ai.model.ChatRequest request) {
				return null;
			}
		}, "j").build();
		assertFalse(m.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
	}

	@Test
	public void testStreamIoFailure() {
		InputStream boom = new FilterInputStream(new ByteArrayInputStream(new byte[0])) {
			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				throw new IOException("stream gone");
			}
		};
		assertThrows(EvalException.class, () -> new JsonlDatasetLoader().load(boom));
		Reader boomReader = new FilterReader(new StringReader("x")) {
			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				throw new IOException("reader gone");
			}
		};
		assertThrows(EvalException.class, () -> new CsvDatasetLoader().load(boomReader));
	}

	@Test
	public void testComparisonMissingMetric() {
		ScriptedChatClient c1 = new ScriptedChatClient("x");
		EvalRunner ra = EvalRunner.builder(c1, "a").addMetric(OutcomeSupport.passMetric()).build();
		EvalReport a = ra.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "x"))));
		ScriptedChatClient c2 = new ScriptedChatClient("x");
		EvalMetric other = new EvalMetric() {
			@Override
			public String name() {
				return "other";
			}

			@Override
			public MetricResult evaluate(EvalCase c, EvalOutcome o, com.sure.ai.evals.metric.EvalContext ctx) {
				return MetricResult.notApplicable("n/a");
			}
		};
		EvalRunner rb = EvalRunner.builder(c2, "b").addMetric(other).build();
		EvalReport b = rb.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "x"))));
		EvalComparison cmp = EvalComparison.of(a, b);
		assertTrue(cmp.meanDiffs().isEmpty() || !cmp.meanDiffs().containsKey("pass_metric")
			|| cmp.meanDiffs().get("pass_metric") == null);
		assertEquals(0, cmp.winsA());
	}

	@Test
	public void testEvalCaseInputTextMulti() {
		EvalCase c = EvalCase.builder()
			.input(List.of(ChatMessage.user("第一段"), ChatMessage.assistant("第二段")))
			.build();
		assertTrue(c.inputText().contains("第一段"));
		assertTrue(c.inputText().contains("第二段"));
	}
}
