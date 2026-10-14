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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

/**
 * {@link EvalRunner} 测试：顺序 / 并发、失败隔离、线程池生命周期、监听钩子。
 */
public class EvalRunnerTest {

	private static EvalDataset dataset() {
		return EvalDataset.of("ut", List.of(
			EvalCase.of("c1", "q1", "a1"),
			EvalCase.of("c2", "q2", "a2"),
			EvalCase.of("c3", "q3", "a3")));
	}

	private static EvalMetric okMetric() {
		return new EvalMetric() {
			@Override
			public String name() {
				return "ok";
			}

			@Override
			public MetricResult evaluate(com.sure.ai.evals.dataset.EvalCase c,
					com.sure.ai.evals.metric.EvalOutcome o,
					com.sure.ai.evals.metric.EvalContext ctx) {
				return MetricResult.pass("always");
			}
		};
	}

	@Test
	public void testSequentialRunStats() {
		ScriptedChatClient client = new ScriptedChatClient("回复1", "回复2", "回复3");
		try (EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(okMetric()).build()) {
			EvalReport report = runner.run(dataset());
			assertEquals("ut", report.datasetName());
			assertEquals(3, report.caseCount());
			assertEquals(0, report.failedCaseCount());
			MetricStats stats = report.metricStats("ok");
			assertEquals(3, stats.applicableCount());
			assertEquals(1.0, stats.mean(), 0.0);
			assertEquals(1.0, stats.passRate(), 0.0);
			assertEquals(15, report.totalTokens());
			assertTrue(report.startedAtMillis() <= report.finishedAtMillis());
		}
	}

	@Test
	public void testFailureIsolation() {
		// 自定义客户端：第二条用例抛异常
		ScriptedChatClient flaky = new ScriptedChatClient("好", "好", "好") {
			@Override
			public com.sure.ai.model.ChatResponse chat(com.sure.ai.model.ChatRequest request) {
				if (request.messages().get(0).content().equals("q2")) {
					throw new RuntimeException("boom");
				}
				return super.chat(request);
			}
		};
		try (EvalRunner runner = EvalRunner.builder(flaky, "m").addMetric(okMetric()).build()) {
			EvalReport report = runner.run(dataset());
			assertEquals(3, report.caseCount());
			assertEquals(1, report.failedCaseCount());
			CaseDetail failed = report.detailByCaseId("c2");
			assertFalse(failed.succeeded());
			assertNotNull(failed.errorMessage());
			assertTrue(report.details().get(1).caseId().equals("c2"));
		}
	}

	@Test
	public void testMetricExceptionIsIsolated() {
		EvalMetric broken = new EvalMetric() {
			@Override
			public String name() {
				return "broken";
			}

			@Override
			public MetricResult evaluate(com.sure.ai.evals.dataset.EvalCase c,
					com.sure.ai.evals.metric.EvalOutcome o,
					com.sure.ai.evals.metric.EvalContext ctx) {
				throw new IllegalStateException("metric boom");
			}
		};
		ScriptedChatClient client = new ScriptedChatClient("x", "y");
		try (EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(broken).addMetric(okMetric()).build()) {
			EvalReport report = runner.run(EvalDataset.of(List.of(
				EvalCase.of("c1", "q1", "a1"), EvalCase.of("c2", "q2", "a2"))));
			assertEquals(2, report.caseCount());
			MetricStats brokenStats = report.metricStats("broken");
			assertEquals(2, brokenStats.nanCount());
			assertEquals(0, brokenStats.applicableCount());
			assertTrue(report.metricStats("ok").applicableCount() == 2);
		}
	}

	@Test
	public void testConcurrencyPreservesOrder() {
		ScriptedChatClient client = new ScriptedChatClient("a", "b", "c", "d", "e");
		try (EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(okMetric()).concurrency(3).build()) {
			List<EvalCase> cases = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				cases.add(EvalCase.of("case-" + i, "q" + i, "a"));
			}
			EvalReport report = runner.run(EvalDataset.of("p", cases));
			assertEquals(5, report.caseCount());
			for (int i = 0; i < 5; i++) {
				assertEquals("case-" + i, report.details().get(i).caseId());
			}
		}
	}

	@Test
	public void testExternalExecutorNotClosed() {
		ExecutorService external = Executors.newSingleThreadExecutor();
		ScriptedChatClient client = new ScriptedChatClient("a");
		try (EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(okMetric()).executorService(external).build()) {
			runner.run(EvalDataset.of(List.of(EvalCase.of("c1", "q", "a"))));
		}
		assertFalse(external.isShutdown());
		external.shutdown();
	}

	@Test
	public void testListenerHooks() {
		List<String> events = new ArrayList<>();
		EvalRunListener listener = new EvalRunListener() {
			@Override
			public void onCaseStarted(EvalCase evalCase) {
				events.add("start:" + evalCase.id());
			}

			@Override
			public void onCaseComplete(EvalCase evalCase,
					com.sure.ai.evals.metric.EvalOutcome outcome,
					Map<String, MetricResult> results) {
				events.add("done:" + evalCase.id());
			}
		};
		EvalRunListener badListener = new EvalRunListener() {
			@Override
			public void onCaseStarted(EvalCase evalCase) {
				throw new RuntimeException("hook boom");
			}
		};
		ScriptedChatClient client = new ScriptedChatClient("a", "b");
		try (EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(okMetric()).addListener(listener).addListener(badListener).build()) {
			EvalReport report = runner.run(EvalDataset.of(List.of(
				EvalCase.of("c1", "q", "a"), EvalCase.of("c2", "q", "a"))));
			assertEquals(2, report.caseCount());
			assertTrue(events.contains("start:c1"));
			assertTrue(events.contains("done:c2"));
		}
	}

	@Test
	public void testEmptyDataset() {
		ScriptedChatClient client = new ScriptedChatClient();
		try (EvalRunner runner = EvalRunner.builder(client, "m").addMetric(okMetric()).build()) {
			EvalReport report = runner.run(EvalDataset.of("empty", List.of()));
			assertEquals(0, report.caseCount());
			assertEquals(0.0, report.latencyP50(), 0.0);
		}
	}

	@Test
	public void testMinConcurrencyDegradesToSequential() {
		ScriptedChatClient client = new ScriptedChatClient("a");
		AtomicInteger hits = new AtomicInteger();
		try (EvalRunner runner = EvalRunner.builder(client, "m")
			.addMetric(okMetric()).concurrency(1).build()) {
			EvalReport report = runner.run(EvalDataset.of(List.of(EvalCase.of("c1", "q", "a"))));
			assertEquals(1, report.caseCount());
			hits.incrementAndGet();
		}
		assertEquals(1, hits.get());
	}
}
