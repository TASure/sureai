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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.sure.ai.evals.assertion.EvalAssertions;
import com.sure.ai.evals.assertion.EvalThreshold;
import com.sure.ai.evals.dataset.CsvDatasetLoader;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.dataset.JsonlDatasetLoader;
import com.sure.ai.evals.metric.ContainsMetric;
import com.sure.ai.evals.metric.EmbeddingSimilarityMetric;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.JsonSchemaValidityMetric;
import com.sure.ai.evals.metric.JudgeMode;
import com.sure.ai.evals.metric.LatencyMetric;
import com.sure.ai.evals.metric.LlmJudgeMetric;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.metric.RegexMetric;
import com.sure.ai.evals.metric.TokenCostMetric;
import com.sure.ai.evals.report.EvalHtmlReportWriter;
import com.sure.ai.evals.report.EvalJsonReportWriter;
import com.sure.ai.evals.report.EvalMarkdownReportWriter;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.model.TokenUsage;

/**
 * 第二轮覆盖率补齐。
 */
public class CoverageGap2Test {

	// ---------- JsonSchemaValidityMetric 分支 ----------

	@Test
	public void testSchemaBranches() {
		// 空文本 / 空 record 无可检字段
		JsonSchemaValidityMetric empty = JsonSchemaValidityMetric.builder(Empty.class).build();
		assertEquals(1.0, empty.evaluate(caseOf(null), successOutcome("{}"), context()).score(), 0.0);
		assertEquals(0.0, empty.evaluate(caseOf(null), successOutcome(""), context()).score(), 0.0);
		// 字段显式 null（对象类型）
		JsonSchemaValidityMetric en = JsonSchemaValidityMetric.builder(EnumUser.class).build();
		assertEquals(1.0, en.evaluate(caseOf(null), successOutcome("{\"role\":null}"), context()).score(), 0.0);
		// 必填字段缺失 + 数字分支
		JsonSchemaValidityMetric req = JsonSchemaValidityMetric.builder(Named.class).build();
		assertEquals(0.0, req.evaluate(caseOf(null), successOutcome("{}"), context()).score(), 0.0);
		assertEquals(1.0, req.evaluate(caseOf(null), successOutcome("{\"name\":\"a\",\"age\":3}"), context()).score(), 0.0);
		// 类型错：期望数字给字符串
		assertTrue(req.evaluate(caseOf(null), successOutcome("{\"name\":\"a\",\"age\":\"3\"}"), context()).score() < 1.0);
	}

	record Empty() {
	}

	record EnumUser(String role) {
	}

	record Named(String name, int age) {
	}

	// ---------- Jsonl 加载器分支 ----------

	@Test
	public void testJsonlIoFailure() {
		Reader boom = new FilterReader(new StringReader("x")) {
			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				throw new IOException("disk gone");
			}
		};
		assertThrows(EvalException.class, () -> new JsonlDatasetLoader().load(boom));
	}

	@Test
	public void testJsonlBadLineSkipAndShape() {
		String text = "not json at all\n{\"input\":[{\"role\":\"user\",\"content\":\"hi\"},42]}\n"
			+ "{\"input\":\"q\",\"id\":\"  \",\"tags\":[\"a\",1,{\"x\":1}],\"expected\":99}\n";
		EvalDataset ds = new JsonlDatasetLoader().load(new StringReader(text));
		assertEquals(2, ds.size());
		assertEquals(1, ds.get(1).tags().size());
		assertNull(ds.get(1).expected());
	}

	@Test
	public void testJsonlExpectedToolsNonArray() {
		String text = "{\"input\":\"q\",\"expectedTools\":\"x\"}\n";
		EvalDataset ds = new JsonlDatasetLoader().load(new StringReader(text));
		assertEquals(0, ds.get(0).expectedTools().size());
	}

	@Test
	public void testJsonlBuilderName() {
		JsonlDatasetLoader l = JsonlDatasetLoader.builder().name("n1").badLineStrategy(JsonlDatasetLoader.BadLineStrategy.SKIP).build();
		assertEquals("n1", l.load(new StringReader("{\"input\":\"q\"}\n")).name());
	}

	// ---------- CSV 分支 ----------

	@Test
	public void testCsvBranches() {
		// 未闭合引号 → EvalException；CRLF；最后一行无换行；metadata 列
		String csv = "input,expected,tags,extra\na,b,t1;u,x\r\nq2,e2,,z\r\n";
		EvalDataset ds = new CsvDatasetLoader().load(new StringReader(csv));
		assertEquals(2, ds.size());
		assertEquals("x", ds.get(0).metadata().get("extra"));
		assertEquals("b", ds.get(0).expected());
		assertThrows(EvalException.class,
			() -> new CsvDatasetLoader().load(new StringReader("input\n\"unclosed\n")));
		assertThrows(EvalException.class, () -> new CsvDatasetLoader().load(Path.of("/no/such.csv")));
	}

	// ---------- LlmJudgeMetric 分支 ----------

	@Test
	public void testLlmJudgeMoreBranches() {
		LlmJudgeMetric noExp = LlmJudgeMetric.builder(new ScriptedChatClient("YES"), "j").maxTokens(0).build();
		assertTrue(noExp.evaluate(caseOf(null), successOutcome("a"), context()).passedAt(1.0));
		LlmJudgeMetric scaleBad = LlmJudgeMetric.builder(new ScriptedChatClient("9"), "j")
			.mode(JudgeMode.SCALE_1_TO_5).build();
		assertFalse(scaleBad.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
		LlmJudgeMetric pctBad = LlmJudgeMetric.builder(new ScriptedChatClient("150"), "j")
			.mode(JudgeMode.SCORE_100).build();
		assertFalse(pctBad.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
		LlmJudgeMetric awin = LlmJudgeMetric.builder(new ScriptedChatClient("实际输出更好"), "j")
			.mode(JudgeMode.PAIRWISE).build();
		assertEquals(1.0, awin.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 0.0);
		LlmJudgeMetric bwin = LlmJudgeMetric.builder(new ScriptedChatClient("答案B"), "j")
			.mode(JudgeMode.PAIRWISE).build();
		assertEquals(0.0, bwin.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 0.0);
		String longText = "是".repeat(200);
		LlmJudgeMetric longOut = LlmJudgeMetric.builder(new ScriptedChatClient(longText), "j").build();
		assertTrue(longOut.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
		// 空文本回复 → 安全结果
		LlmJudgeMetric blank = LlmJudgeMetric.builder(new ScriptedChatClient(""), "j").build();
		assertFalse(blank.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
	}

	// ---------- 确定性指标分支 ----------

	@Test
	public void testMetricBranches() {
		assertEquals("contains", ContainsMetric.builder().build().name());
		ContainsMetric any = ContainsMetric.builder().delimiter(null).build();
		assertEquals(0.0, any.evaluate(caseOf("x"), successOutcome("y"), context()).score(), 0.0);
		ContainsMetric all = ContainsMetric.builder().allRequired(true).build();
		assertEquals(0.0, all.evaluate(caseOf("a;b"), successOutcome("only a"), context()).score(), 0.0);
		LatencyMetric lm = LatencyMetric.builder().targetMs(100).limitMs(200).passThreshold(0.5).build();
		assertEquals(0.5, lm.passThreshold(), 0.0);
		assertEquals(0.5, lm.evaluate(caseOf(null), successOutcome("x", null, 150_000_000L), context()).score(), 0.0);
		TokenCostMetric tc = TokenCostMetric.builder().targetTokens(100).limitTokens(200).passThreshold(0.4).build();
		assertEquals(0.4, tc.passThreshold(), 0.0);
		assertEquals(0.0, tc.evaluate(caseOf(null),
			successOutcome("x", TokenUsage.of(0, 300, 300), 0L), context()).score(), 0.0);
		assertEquals("regex", RegexMetric.builder().build().name());
		RegexMetric longRe = RegexMetric.builder().build();
		assertEquals(1.0, longRe.evaluate(caseOf("a".repeat(100)), successOutcome("a".repeat(100)), context()).score(), 0.0);
		ScriptedEmbeddingClient emptyEmb = new ScriptedEmbeddingClient(new float[0]);
		EmbeddingSimilarityMetric es = EmbeddingSimilarityMetric.builder(emptyEmb, "e").build();
		assertFalse(es.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
		ScriptedEmbeddingClient zeroEmb = new ScriptedEmbeddingClient(new float[] { 0f, 0f }, new float[] { 0f, 0f });
		EmbeddingSimilarityMetric ze = EmbeddingSimilarityMetric.builder(zeroEmb, "e").build();
		assertFalse(ze.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
	}

	// ---------- EvalRunner 分支 ----------

	@Test
	public void testRunnerListenerExceptionAndNullMetric() {
		ScriptedChatClient client = new ScriptedChatClient("好", "好");
		EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(new EvalMetric() {
				@Override
				public String name() {
					return "broken";
				}

				@Override
				public MetricResult evaluate(EvalCase c, EvalOutcome o, com.sure.ai.evals.metric.EvalContext ctx) {
					return null;
				}
			})
			.addListener(new EvalRunListener() {
				@Override
				public void onCaseComplete(EvalCase ec, EvalOutcome eo,
						java.util.Map<String, MetricResult> results) {
					throw new RuntimeException("listener boom");
				}
			})
			.build();
		EvalReport report = runner.run(EvalDataset.of("r", List.of(EvalCase.of("c1", "q", "a1"))));
		assertEquals(1, report.details().size());
	}

	@Test
	public void testRunnerErrorWithoutMessage() {
		ScriptedChatClient client = new ScriptedChatClient("好") {
			@Override
			public com.sure.ai.model.ChatResponse chat(com.sure.ai.model.ChatRequest request) {
				throw new RuntimeException();
			}
		};
		EvalRunner runner = EvalRunner.builder(client, "m").addMetric(OutcomeSupport.passMetric()).build();
		EvalReport report = runner.run(EvalDataset.of("r", List.of(EvalCase.of("c1", "q", "a"))));
		assertFalse(report.details().get(0).succeeded());
		assertNotNull(report.details().get(0).errorMessage());
	}

	@Test
	public void testRunnerAddMetricsList() {
		EvalRunner runner = EvalRunner.builder(new ScriptedChatClient(), "m")
			.metrics(java.util.List.of(OutcomeSupport.passMetric())).build();
		assertNotNull(runner);
	}

	// ---------- 报告写出器分支 ----------

	@Test
	public void testWritersBranches() {
		ScriptedChatClient flaky = new ScriptedChatClient("好", "好") {
			@Override
			public com.sure.ai.model.ChatResponse chat(com.sure.ai.model.ChatRequest request) {
				if (request.messages().get(0).content().equals("q2")) {
					throw new RuntimeException("boom");
				}
				return super.chat(request);
			}
		};
		EvalMetric naMetric = new EvalMetric() {
			@Override
			public String name() {
				return "na";
			}

			@Override
			public MetricResult evaluate(EvalCase c, EvalOutcome o, com.sure.ai.evals.metric.EvalContext ctx) {
				return MetricResult.notApplicable("n/a");
			}
		};
		EvalRunner runner = EvalRunner.builder(flaky, "m")
			.addMetric(OutcomeSupport.passMetric()).addMetric(naMetric).build();
		EvalReport report = runner.run(EvalDataset.of("r", List.of(
			EvalCase.of("c1", "q1", "a1"), EvalCase.of("c2", "q2", "a2"))));
		String md = new EvalMarkdownReportWriter().render(report);
		assertTrue(md.contains("失败用例"));
		assertTrue(md.contains("N/A"));
		String html = new EvalHtmlReportWriter().render(report);
		assertTrue(html.contains("N/A"));
		String json = new EvalJsonReportWriter().render(report);
		assertTrue(json.contains("\"na\""));
	}

	// ---------- 断言与 Util ----------

	@Test
	public void testAssertionsMissingMetric() {
		ScriptedChatClient client = new ScriptedChatClient("好");
		EvalRunner runner = EvalRunner.builder(client, "m").addMetric(OutcomeSupport.passMetric()).build();
		EvalReport report = runner.run(EvalDataset.of("r", List.of(EvalCase.of("c1", "q", "好"))));
		AssertionError e = assertThrows(AssertionError.class,
			() -> EvalAssertions.assertMeets(report, EvalThreshold.of("missing", 0.9)));
		assertTrue(e.getMessage().contains("缺失"));
	}

	@Test
	public void testPrivateConstructorsUnavailable() throws Exception {
		Constructor<EvalAssertions> c1 = EvalAssertions.class.getDeclaredConstructor();
		c1.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, () -> c1.newInstance());
		Constructor<EvalUtil> c2 = EvalUtil.class.getDeclaredConstructor();
		c2.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, () -> c2.newInstance());
	}

	@Test
	public void testUtilVarargsRunner() {
		ScriptedChatClient client = new ScriptedChatClient("好");
		try (EvalRunner r = EvalUtil.runner(client, "m", OutcomeSupport.passMetric())) {
			assertNotNull(r);
		}
	}

	// ---------- 对比分支 ----------

	@Test
	public void testComparisonTieAndMissing() {
		ScriptedChatClient c1 = new ScriptedChatClient("x");
		EvalRunner ra = EvalRunner.builder(c1, "a").addMetric(OutcomeSupport.passMetric()).build();
		EvalReport a = ra.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "x"), EvalCase.of("c9", "q", "x"))));
		ScriptedChatClient c2 = new ScriptedChatClient("x");
		EvalRunner rb = EvalRunner.builder(c2, "b")
			.addMetric(OutcomeSupport.passMetric()).addMetric(naMetric()).build();
		EvalReport b = rb.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "x"))));
		EvalComparison cmp = EvalComparison.of(a, b);
		assertEquals(0, cmp.winsA());
		assertEquals(1, cmp.ties());
		assertEquals(b, cmp.reportB());
	}

	private static EvalMetric naMetric() {
		return new EvalMetric() {
			@Override
			public String name() {
				return "na";
			}

			@Override
			public MetricResult evaluate(EvalCase c, EvalOutcome o, com.sure.ai.evals.metric.EvalContext ctx) {
				return MetricResult.notApplicable("n/a");
			}
		};
	}
}
