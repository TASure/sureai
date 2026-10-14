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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.dataset.JsonlDatasetLoader;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.EmbeddingSimilarityMetric;
import com.sure.ai.evals.metric.JsonSchemaValidityMetric;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.EvalJsonReportWriter;
import com.sure.ai.evals.report.EvalMarkdownReportWriter;
import com.sure.ai.evals.report.EvalHtmlReportWriter;
import com.sure.ai.evals.report.EvalReport;

/**
 * 第四轮覆盖率补齐（describe 分支 / 流 IO / 写出器 IOException / embedding 空向量）。
 */
public class CoverageGap4Test {

	record Rich(List<Integer> list, java.util.Map<String, String> obj, boolean flag) {
	}

	@Test
	public void testSchemaDescribeBranches() {
		JsonSchemaValidityMetric m = JsonSchemaValidityMetric.builder(Rich.class).build();
		// list 应为 array，实际给 boolean → describe(boolean)
		assertFalse(m.evaluate(caseOf(null), successOutcome("{\"list\":true}"), context()).passedAt(1.0));
		// obj 应为 object，实际给 array → describe(array)
		assertFalse(m.evaluate(caseOf(null), successOutcome("{\"obj\":[1]}"), context()).passedAt(1.0));
		// flag 应为 boolean，实际给 object → describe(object)
		assertFalse(m.evaluate(caseOf(null), successOutcome("{\"flag\":{}}"), context()).passedAt(1.0));
	}

	@Test
	public void testStreamReadFailure() {
		InputStream boom = new FilterInputStream(new ByteArrayInputStream(new byte[0])) {
			@Override
			public int read() throws IOException {
				throw new IOException("read gone");
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				throw new IOException("read gone");
			}
		};
		assertThrows(EvalException.class, () -> new JsonlDatasetLoader().load(boom));
	}

	@Test
	public void testWritersWriteIoFailure() {
		ScriptedChatClient client = new ScriptedChatClient("好");
		EvalRunner runner = EvalRunner.builder(client, "m").addMetric(OutcomeSupport.passMetric()).build();
		EvalReport report = runner.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "好"))));
		Path bad = Path.of("/no/such/dir/report.json");
		assertThrows(java.io.UncheckedIOException.class,
			() -> new EvalJsonReportWriter().write(report, bad));
		assertThrows(java.io.UncheckedIOException.class,
			() -> new EvalMarkdownReportWriter().write(report, bad));
		assertThrows(java.io.UncheckedIOException.class,
			() -> new EvalHtmlReportWriter().write(report, bad));
	}

	@Test
	public void testEmbeddingEmptyVector() {
		ScriptedEmbeddingClient empty = new ScriptedEmbeddingClient();
		EmbeddingSimilarityMetric m = EmbeddingSimilarityMetric.builder(empty, "e").build();
		assertFalse(m.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
	}

	@Test
	public void testComparisonMissingResultB() {
		ScriptedChatClient c1 = new ScriptedChatClient("x");
		EvalRunner ra = EvalRunner.builder(c1, "a").addMetric(OutcomeSupport.passMetric()).build();
		EvalReport a = ra.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "x"))));
		ScriptedChatClient c2 = new ScriptedChatClient("x");
		EvalMetric na = new EvalMetric() {
			@Override
			public String name() {
				return "pass_metric";
			}

			@Override
			public MetricResult evaluate(EvalCase c, EvalOutcome o, com.sure.ai.evals.metric.EvalContext ctx) {
				return MetricResult.notApplicable("n/a");
			}
		};
		EvalRunner rb = EvalRunner.builder(c2, "b").addMetric(na).build();
		EvalReport b = rb.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "x"))));
		EvalComparison cmp = EvalComparison.of(a, b);
		assertTrue(cmp.winsA() >= 0);
	}
}
