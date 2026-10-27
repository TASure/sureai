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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sure.ai.evals.assertion.EvalAssertions;
import com.sure.ai.evals.assertion.EvalThreshold;
import com.sure.ai.evals.dataset.CsvDatasetLoader;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.dataset.JsonlDatasetLoader;
import com.sure.ai.evals.metric.ContainsMetric;
import com.sure.ai.evals.metric.EmbeddingSimilarityMetric;
import com.sure.ai.evals.metric.ExactMatchMetric;
import com.sure.ai.evals.metric.JsonSchemaValidityMetric;
import com.sure.ai.evals.metric.LatencyMetric;
import com.sure.ai.evals.metric.LlmJudgeMetric;
import com.sure.ai.evals.metric.RegexMetric;
import com.sure.ai.evals.metric.TokenCostMetric;
import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalJsonReportWriter;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.model.TokenUsage;

/**
 * 覆盖率补齐：加载器 IO 路径、指标分支、runner 异常路径、报告写出器分支、断言与对比。
 */
public class CoverageGapTest {

	@Test
	public void testDatasetLoaderPathAndStream() throws Exception {
		Path file = Files.createTempFile("ds", ".csv");
		Files.writeString(file, "input,expected\nq,a\n");
		EvalDataset ds = new CsvDatasetLoader().load(file);
		assertEquals(1, ds.size());
		EvalDataset ds2 = new CsvDatasetLoader()
			.load(new ByteArrayInputStream("input\nq\n".getBytes(StandardCharsets.UTF_8)));
		assertEquals(1, ds2.size());
		// IO 失败 → EvalException
		assertThrows(EvalException.class, () -> new CsvDatasetLoader().load(Path.of("/no/such/file.csv")));
	}

	@Test
	public void testJsonlFailBranches() {
		JsonlDatasetLoader fail = JsonlDatasetLoader.builder()
			.badLineStrategy(JsonlDatasetLoader.BadLineStrategy.FAIL).build();
		assertThrows(EvalException.class, () -> fail.load(new StringReader("[1,2]\n")));
		assertThrows(EvalException.class, () -> fail.load(new StringReader("{\"expected\":\"x\"}\n")));
	}

	@Test
	public void testJsonlMetadataAndArrays() {
		String jsonl = "{\"input\":\"q\",\"tags\":[\"a\",1],\"metadata\":{\"b\":true,\"n\":1.5,"
			+ "\"arr\":[1,2],\"nil\":null},\"expectedTools\":[\"t1\"]}\n";
		EvalDataset ds = new JsonlDatasetLoader().load(new StringReader(jsonl));
		Map<String, Object> meta = ds.get(0).metadata();
		assertEquals(true, meta.get("b"));
		assertEquals(1.5, meta.get("n"));
		assertEquals(List.of(1L, 2L), meta.get("arr"));
		assertFalse(meta.containsKey("nil"));
		assertEquals(List.of("a"), ds.get(0).tags());
		assertEquals(List.of("t1"), ds.get(0).expectedTools());
	}

	@Test
	public void testJsonlNonStringInputArrayItem() {
		String jsonl = "{\"input\":[{\"role\":123,\"content\":456},\"纯文本\"]}\n";
		EvalDataset ds = new JsonlDatasetLoader().load(new StringReader(jsonl));
		assertEquals(2, ds.get(0).input().size());
		assertEquals("纯文本", ds.get(0).input().get(1).content());
	}

	@Test
	public void testJsonlToolsAliasKey() {
		String jsonl = "{\"input\":\"q\",\"expected_tools\":[\"a\",\"b\"]}\n";
		EvalDataset ds = new JsonlDatasetLoader().load(new StringReader(jsonl));
		assertEquals(List.of("a", "b"), ds.get(0).expectedTools());
	}

	@Test
	public void testCsvBuilderAndGaps() {
		CsvDatasetLoader loader = CsvDatasetLoader.builder()
			.name("n").tagsSeparator("|").build();
		assertEquals("n", loader.load(new StringReader("input\nq\n")).name());
		// 表头缺 input 列 → 全部坏行 SKIP
		EvalDataset ds = new CsvDatasetLoader().load(new StringReader("foo,bar\n1,2\n"));
		assertEquals(0, ds.size());
	}

	@Test
	public void testSchemaExtraBranches() {
		JsonSchemaValidityMetric m = JsonSchemaValidityMetric.builder(Person.class)
			.name("schema2").build();
		// 缺可选字段 + 类型错
		assertEquals(0.0, m.evaluate(caseOf(null), successOutcome("{\"age\":\"x\"}"), context()).score(), 0.0);
		// array/object/boolean 类型校验
		JsonSchemaValidityMetric rich = JsonSchemaValidityMetric.builder(Rich.class).build();
		assertEquals(1.0, rich.evaluate(caseOf(null),
			successOutcome("{\"list\":[1],\"obj\":{},\"flag\":true}"), context()).score(), 0.0);
		assertTrue(rich.evaluate(caseOf(null),
			successOutcome("{\"list\":1}"), context()).score() < 1.0);
	}

	record Person(int age) {
	}

	record Rich(java.util.List<Integer> list, java.util.Map<String, String> obj, boolean flag) {
	}

	@Test
	public void testLlmJudgeBranches() {
		// 二元判定歧义 → notApplicable
		LlmJudgeMetric amb = LlmJudgeMetric.builder(new ScriptedChatClient("支持，但也不支持"), "j").build();
		assertFalse(amb.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
		// 分量表无数字 → NA
		LlmJudgeMetric noNum = LlmJudgeMetric.builder(new ScriptedChatClient("没有数字"), "j")
			.mode(com.sure.ai.evals.metric.JudgeMode.SCALE_1_TO_5).build();
		assertFalse(noNum.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
		// 百分制小数直接归一
		LlmJudgeMetric pct = LlmJudgeMetric.builder(new ScriptedChatClient("0.75"), "j")
			.mode(com.sure.ai.evals.metric.JudgeMode.SCORE_100).build();
		assertEquals(0.75, pct.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 1e-9);
		// pairwise 平局中文
		LlmJudgeMetric tie = LlmJudgeMetric.builder(new ScriptedChatClient("两者平"), "j")
			.mode(com.sure.ai.evals.metric.JudgeMode.PAIRWISE).build();
		assertEquals(0.5, tie.evaluate(caseOf("r"), successOutcome("a"), context()).score(), 1e-9);
		// pairwise 歧义 → NA
		LlmJudgeMetric amb2 = LlmJudgeMetric.builder(new ScriptedChatClient("说不清"), "j")
			.mode(com.sure.ai.evals.metric.JudgeMode.PAIRWISE).build();
		assertFalse(amb2.evaluate(caseOf("r"), successOutcome("a"), context()).applicable());
		// 空 rubric 模板默认分支
		LlmJudgeMetric def = LlmJudgeMetric.builder(new ScriptedChatClient("YES"), "j").build();
		assertTrue(def.evaluate(caseOf("r"), successOutcome("a"), context()).passedAt(1.0));
	}

	@Test
	public void testDeterministicBranches() {
		ContainsMetric allEmpty = ContainsMetric.builder().delimiter(";").allRequired(true).build();
		assertFalse(allEmpty.evaluate(caseOf("; ;"), successOutcome("x"), context()).applicable());
		ExactMatchMetric ex = ExactMatchMetric.builder().normalizeWhitespace(false).build();
		assertFalse(ex.evaluate(caseOf("a b"), successOutcome("a  b"), context()).passedAt(1.0));
		RegexMetric rx = RegexMetric.builder().mode(RegexMetric.Mode.MATCHES).build();
		assertTrue(rx.evaluate(caseOf("^abc$"), successOutcome("abc"), context()).passedAt(1.0));
		LatencyMetric lm = LatencyMetric.builder().targetMs(100).limitMs(200).build();
		assertEquals(1.0, lm.evaluate(caseOf(null), successOutcome("x", null, 50_000_000L), context()).score(), 0.0);
		TokenCostMetric tc = TokenCostMetric.builder().targetTokens(100).limitTokens(200).build();
		assertEquals(1.0, tc.evaluate(caseOf(null),
			successOutcome("x", TokenUsage.of(5, 5, 100), 0L), context()).score(), 0.0);
		assertEquals(0.0, tc.evaluate(caseOf(null),
			successOutcome("x", TokenUsage.of(100, 100, 200), 0L), context()).score(), 0.0);
		ScriptedEmbeddingClient emb = new ScriptedEmbeddingClient(new float[] { 1f }, new float[] { 1f });
		EmbeddingSimilarityMetric es = EmbeddingSimilarityMetric.builder(emb, "e").name("sim").build();
		assertEquals("sim", es.name());
	}

	@Test
	public void testRunnerFutureExceptionPaths() throws Exception {
		// future.get() 抛 ExecutionException 分支
		ExecutorService exec1 = new BrokenExecutor(new ExecutionException("boom", new RuntimeException("x")));
		ScriptedChatClient client = new ScriptedChatClient("a");
		EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(OutcomeSupport.passMetric()).executorService(exec1).build();
		assertThrows(EvalException.class,
			() -> runner.run(EvalDataset.of(List.of(EvalCase.of("c1", "q", "a")))));
		exec1.shutdown();
		// future.get() 抛 InterruptedException 分支
		ExecutorService exec2 = new BrokenExecutor(new InterruptedException("x"));
		EvalRunner runner2 = EvalRunner.builder(new ScriptedChatClient("a"), "m")
			.addMetric(OutcomeSupport.passMetric()).executorService(exec2).build();
		assertThrows(EvalException.class,
			() -> runner2.run(EvalDataset.of(List.of(EvalCase.of("c1", "q", "a")))));
		// runner 已恢复中断标志，此处按 CI 规则显式清除
		Thread.interrupted();
		exec2.shutdown();
	}

	@Test
	public void testRunnerInternalPoolClose() {
		ScriptedChatClient client = new ScriptedChatClient();
		EvalRunner runner = EvalRunner.builder(client, "m").concurrency(2).build();
		runner.run(EvalDataset.of(List.of()));
		runner.close();
	}

	@Test
	public void testReportWriterBranches() {
		ScriptedChatClient client = new ScriptedChatClient("好", "好");
		EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(OutcomeSupport.passMetric()).build();
		EvalReport report = runner.run(EvalDataset.of("r", List.of(
			EvalCase.of("c1", "q1", "a1"), EvalCase.of("c2", "q2", "a2"))));
		String json = new EvalJsonReportWriter().render(report);
		assertTrue(json.contains("\"usage\""));
		Path tmp = Path.of(System.getProperty("java.io.tmpdir"), "evals-r.json");
		new EvalJsonReportWriter().write(report, tmp);
		assertTrue(Files.exists(tmp));
	}

	@Test
	public void testAssertionsWithFailedCase() {
		ScriptedChatClient flaky = new ScriptedChatClient("好", "好") {
			@Override
			public com.sure.ai.model.ChatResponse chat(com.sure.ai.model.ChatRequest request) {
				if (request.messages().get(0).content().equals("q2")) {
					throw new RuntimeException("boom");
				}
				return super.chat(request);
			}
		};
		EvalRunner runner = EvalRunner.builder(flaky, "m")
			.addMetric(ExactMatchMetric.builder().build()).build();
		EvalReport report = runner.run(EvalDataset.of("r", List.of(
			EvalCase.of("c1", "q1", "a1"), EvalCase.of("c2", "q2", "a2"))));
		AssertionError e = assertThrows(AssertionError.class,
			() -> EvalAssertions.assertMeets(report, EvalThreshold.of("exact_match", 0.99)));
		assertTrue(e.getMessage().contains("失败用例"));
		CaseDetail failed = report.detailByCaseId("c2");
		assertFalse(failed.succeeded());
	}

	@Test
	public void testUtilDefaultRunnerNotInit() {
		assertThrows(IllegalStateException.class, EvalUtil::defaultRunner);
	}

	@Test
	public void testComparisonBothDirections() {
		ScriptedChatClient client = new ScriptedChatClient("x", "y");
		EvalRunner ra = EvalRunner.builder(client, "a").addMetric(OutcomeSupport.passMetric()).build();
		EvalReport a = ra.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "x"))));
		ScriptedChatClient client2 = new ScriptedChatClient("x", "y");
		com.sure.ai.evals.metric.EvalMetric zero = new com.sure.ai.evals.metric.EvalMetric() {
			@Override
			public String name() {
				return "pass_metric";
			}

			@Override
			public com.sure.ai.evals.metric.MetricResult evaluate(com.sure.ai.evals.dataset.EvalCase c,
					com.sure.ai.evals.metric.EvalOutcome o,
					com.sure.ai.evals.metric.EvalContext ctx) {
				return com.sure.ai.evals.metric.MetricResult.fail("zero");
			}
		};
		EvalRunner rb = EvalRunner.builder(client2, "b").addMetric(zero).build();
		EvalReport b = rb.run(EvalDataset.of("d", List.of(EvalCase.of("c1", "q", "x"))));
		EvalComparison cmp = EvalComparison.of(a, b);
		assertEquals(1, cmp.winsA());
		assertEquals(0, cmp.winsB());
		// 反向：B 为正时 B 胜
		EvalComparison reverse = EvalComparison.of(b, a);
		assertEquals(1, reverse.winsB());
		assertEquals(1.0 - 0.0, cmp.meanDiffs().get("pass_metric"), 1e-9);
	}

	/** 提交即返回固定失败 Future 的线程池。 */
	static final class BrokenExecutor extends java.util.concurrent.AbstractExecutorService {
		private final Throwable failure;
		private final ExecutorService delegate = Executors.newSingleThreadExecutor();

		BrokenExecutor(Throwable failure) {
			this.failure = failure;
		}

		@Override
		public void execute(Runnable command) {
			delegate.execute(command);
		}

		@Override
		public <T> Future<T> submit(java.util.concurrent.Callable<T> task) {
			return new Future<T>() {
				@Override
				public boolean cancel(boolean mayInterruptIfRunning) {
					return false;
				}

				@Override
				public boolean isCancelled() {
					return false;
				}

				@Override
				public boolean isDone() {
					return true;
				}

				@Override
				public T get() throws InterruptedException, ExecutionException {
					if (BrokenExecutor.this.failure instanceof RuntimeException re) {
						throw re;
					}
					if (BrokenExecutor.this.failure instanceof InterruptedException ie) {
						throw ie;
					}
					throw new ExecutionException(BrokenExecutor.this.failure);
				}

				@Override
				public T get(long timeout, TimeUnit unit)
						throws InterruptedException, ExecutionException {
					return get();
				}
			};
		}

		@Override
		public void shutdown() {
			delegate.shutdown();
		}

		@Override
		public List<Runnable> shutdownNow() {
			return delegate.shutdownNow();
		}

		@Override
		public boolean isShutdown() {
			return delegate.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return delegate.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return true;
		}
	}
}
