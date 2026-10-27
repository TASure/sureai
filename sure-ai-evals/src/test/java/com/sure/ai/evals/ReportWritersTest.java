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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.EvalHtmlReportWriter;
import com.sure.ai.evals.report.EvalJsonReportWriter;
import com.sure.ai.evals.report.EvalMarkdownReportWriter;
import com.sure.ai.evals.report.EvalReport;

/**
 * 三种报告写出器测试。
 */
public class ReportWritersTest {

	private static EvalReport sampleReport(boolean withFailure) {
		ScriptedChatClient client = new ScriptedChatClient("输出<a>&b</a>", "x|y\nz");
		EvalMetric metric = new EvalMetric() {
			@Override
			public String name() {
				return "exact_match";
			}

			@Override
			public MetricResult evaluate(com.sure.ai.evals.dataset.EvalCase c,
					com.sure.ai.evals.metric.EvalOutcome o,
					com.sure.ai.evals.metric.EvalContext ctx) {
				if (o.succeeded() && "a1".equals(o.actualText())) {
					return MetricResult.pass("hit");
				}
				return MetricResult.fail("miss");
			}
		};
		ScriptedChatClient flaky = new ScriptedChatClient("输出<a>&b</a>", "x") {
			@Override
			public com.sure.ai.model.ChatResponse chat(com.sure.ai.model.ChatRequest request) {
				if (withFailure && request.messages().get(0).content().equals("q2")) {
					throw new RuntimeException("boom");
				}
				return super.chat(request);
			}
		};
		try (EvalRunner runner = EvalRunner.builder(flaky, "test-model").addMetric(metric).build()) {
			return runner.run(EvalDataset.of("ds", List.of(
				EvalCase.builder().id("c1").userInput("q1").expected("a1")
					.tags(List.of("t1<a>&b", "t1|pipe")).build(),
				EvalCase.of("c2", "q2", "a2"))));
		}
	}

	@Test
	public void testJsonWriter() {
		EvalReport report = sampleReport(true);
		String json = new EvalJsonReportWriter().render(report);
		assertTrue(json.contains("\"datasetName\":\"ds\""));
		assertTrue(json.contains("\"model\":\"test-model\""));
		assertTrue(json.contains("\"exact_match\""));
		assertTrue(json.contains("\"succeeded\":false"));
		assertTrue(json.contains("boom"));
		assertFalse(json.contains("http://"));
	}

	@Test
	public void testMarkdownWriterEscapes() {
		EvalReport report = sampleReport(false);
		String md = new EvalMarkdownReportWriter().render(report);
		assertTrue(md.contains("| 指标 |"));
		assertTrue(md.contains("t1\\|pipe"));
		assertTrue(md.contains("exact_match"));
	}

	@Test
	public void testHtmlWriterSelfContained() {
		EvalReport report = sampleReport(true);
		String html = new EvalHtmlReportWriter().render(report);
		assertTrue(html.startsWith("<!DOCTYPE html>"));
		assertTrue(html.contains("t1&lt;a&gt;&amp;b"));
		assertFalse(html.contains("cdn"));
		assertFalse(html.contains("http://"));
		assertTrue(html.contains("<style>"));
	}

	@Test
	public void testWritersWriteToFile() throws Exception {
		EvalReport report = sampleReport(false);
		Path dir = Files.createTempDirectory("evals");
		new EvalJsonReportWriter().write(report, dir.resolve("r.json"));
		new EvalMarkdownReportWriter().write(report, dir.resolve("r.md"));
		new EvalHtmlReportWriter().write(report, dir.resolve("r.html"));
		assertTrue(Files.readString(dir.resolve("r.json")).contains("ds"));
		assertTrue(Files.readString(dir.resolve("r.md")).contains("指标汇总"));
		assertTrue(Files.readString(dir.resolve("r.html")).contains("</html>"));
	}
}
