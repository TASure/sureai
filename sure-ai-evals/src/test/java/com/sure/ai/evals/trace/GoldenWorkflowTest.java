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

package com.sure.ai.evals.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.sure.ai.evals.EvalRunner;
import com.sure.ai.evals.EvalUtil;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.EvalContext;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;

/**
 * golden dataset 工作流端到端：脚本后端录制 → JSONL → 离线回放重跑 → 指标回归（零真实网络）。
 */
public class GoldenWorkflowTest {

	/** 恒为通过的占位指标：只验证「回放结果与录制一致」，不纠结文本内容。 */
	private static final class PassMetric implements EvalMetric {
		@Override
		public String name() {
			return "pass";
		}

		@Override
		public MetricResult evaluate(EvalCase caseRef, EvalOutcome outcome, EvalContext context) {
			return MetricResult.pass("ok");
		}
	}

	@Test
	public void 录制回放回归全链路() throws Exception {
		EvalDataset dataset = EvalDataset.of("golden", List.of(
			EvalCase.of("c1", "q1", "期望A"),
			EvalCase.of("c2", "q2", "期望B"),
			EvalCase.of("c3", "q3", "期望C")));
		ScriptClient script = new ScriptClient(
			"回答A", "回答B", "回答C");

		Path file = Files.createTempFile("golden", ".jsonl");
		// 录制当次：真实脚本后端 → JSONL
		EvalReport baseline;
		try (RecordingClient recording = RecordingClient.record(script, file);
				EvalRunner runner = EvalRunner.builder(recording, "test-model")
					.addMetric(new PassMetric()).build()) {
			baseline = runner.run(dataset);
		}
		assertEquals(3, script.calls);
		assertEquals(3, Files.readAllLines(file).size());

		// 关键证明：把脚本后端武装成「一经调用即炸」——回放全程零真实调用
		script.alwaysThrow(new RuntimeException("回放模式不得触碰真实后端"));

		// 回放当次：离线客户端重跑同一数据集
		EvalReport current;
		try (RecordingClient replay = RecordingClient.replay(file);
				EvalRunner runner = EvalRunner.builder(replay, "test-model")
					.addMetric(new PassMetric()).build()) {
			current = runner.run(dataset);
		}

		// 输出文本与录制逐用例一致
		assertEquals(baseline.details().size(), current.details().size());
		for (int i = 0; i < baseline.details().size(); i++) {
			CaseDetail b = baseline.details().get(i);
			CaseDetail c = current.details().get(i);
			assertEquals(b.caseId(), c.caseId());
			assertEquals(b.actualText(), c.actualText());
		}
		// usage 保留
		assertEquals(baseline.totalTokens(), current.totalTokens());
		// 回归：baseline=录制当次，current=回放当次，应无退化
		TraceRegression.RegressionResult result = TraceRegression.compare(baseline, current);
		assertTrue(result.summary(), result.passed());
		EvalUtil.assertNoRegression(baseline, current);
		// 回放未触发任何真实调用（脚本调用次数仍为 3）
		assertEquals(3, script.calls);
	}
}
