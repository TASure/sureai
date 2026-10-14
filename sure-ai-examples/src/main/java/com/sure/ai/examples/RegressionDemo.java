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
package com.sure.ai.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.evals.EvalRunner;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.EvalContext;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.trace.RecordingClient;
import com.sure.ai.evals.trace.TraceRegression;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ChatMessage;

/**
 * 录制回放与 golden dataset 回归（v2.8.0）离线示例：
 * 脚本后端录制 → JSONL 落盘 → 离线回放重跑 → {@link TraceRegression} 基线回归。
 *
 * <p>本 Demo 完全离线：{@link ScriptClient} 按队列返回预设答案；录制当次它被真实调用 3 次后，
 * 被「武装成一经调用即抛异常」——随后的回放当次若触碰真实后端会立刻炸掉，从而证明回放
 * 全程零网络。生产环境把 {@link ScriptClient} 换成任意平台客户端即可录真实基线。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class RegressionDemo {

	private RegressionDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 * @throws Exception 临时文件创建失败时抛出
	 */
	public static void main(String[] args) throws Exception {
		EvalDataset dataset = EvalDataset.of("golden-demo", List.of(
				EvalCase.of("c1", "问题一", "期望A"),
				EvalCase.of("c2", "问题二", "期望B"),
				EvalCase.of("c3", "问题三", "期望C")));

		// 占位指标：只验证「回放结果与录制一致」，不纠结文本内容
		EvalMetric passthrough = new EvalMetric() {
			@Override
			public String name() {
				return "passthrough";
			}

			@Override
			public MetricResult evaluate(EvalCase c, EvalOutcome outcome, EvalContext context) {
				return MetricResult.pass("ok");
			}
		};

		ScriptClient backend = new ScriptClient("回答A", "回答B", "回答C");
		Path golden = Files.createTempFile("golden-demo", ".jsonl");

		// ① 录制当次：真实（脚本）后端包一层 RecordingClient，请求/响应成对追加一行 JSONL
		EvalReport baseline;
		try (RecordingClient recording = RecordingClient.record(backend, golden);
				EvalRunner runner = EvalRunner.builder(recording, "demo-model")
						.addMetric(passthrough).build()) {
			baseline = runner.run(dataset);
		}
		System.out.println("录制完成，用例数=" + baseline.details().size()
				+ "，JSONL 行数=" + Files.readAllLines(golden).size());

		// ② 关键证明：脚本后端一经调用即炸——回放全程不得触碰真实后端
		backend.armed = true;

		// ③ 回放当次：离线客户端重跑同一数据集，零网络
		EvalReport current;
		try (RecordingClient replay = RecordingClient.replay(golden);
				EvalRunner runner = EvalRunner.builder(replay, "demo-model")
						.addMetric(passthrough).build()) {
			current = runner.run(dataset);
		}

		// ④ 回归判定：基线 vs 当前，均值退化超容差（默认 0.02）即判退化
		TraceRegression.RegressionResult result = TraceRegression.compare(baseline, current);
		System.out.println(result.summary());
		result.assertPassed();
		System.out.println("golden 回归通过：回放与录制一致，无退化");
	}

	/**
	 * 脚本化后端：按队列返回预设答案；{@code armed=true} 后任何调用都抛异常（证明回放零网络）。
	 */
	private static final class ScriptClient implements AiClient {
		private final List<String> replies;
		private int cursor;
		private boolean armed;

		ScriptClient(String... replies) {
			this.replies = List.of(replies);
		}

		@Override
		public String name() {
			return "script-backend";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			if (this.armed) {
				throw new IllegalStateException("回放模式不得触碰真实后端");
			}
			String reply = this.replies.get(this.cursor++);
			return ChatResponse.of("script-resp", request.model(),
					List.of(Choice.of(0, ChatMessage.assistant(reply), "stop")),
					TokenUsage.of(4, 8, 12), null);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}
}
