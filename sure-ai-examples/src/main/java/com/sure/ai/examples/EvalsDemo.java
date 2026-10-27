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

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.evals.EvalRunner;
import com.sure.ai.evals.assertion.EvalAssertions;
import com.sure.ai.evals.assertion.EvalThreshold;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.ExactMatchMetric;
import com.sure.ai.evals.metric.JudgeMode;
import com.sure.ai.evals.metric.LlmJudgeMetric;
import com.sure.ai.evals.report.EvalMarkdownReportWriter;
import com.sure.ai.evals.report.EvalJsonReportWriter;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;

/**
 * 通用评估（v2.8.0）离线示例：数据集 → 确定性指标 + LLM-as-judge → EvalRunner
 * → Markdown / JSON 报告 → CI 阈值断言。
 *
 * <p>本 Demo 完全离线：内置两个脚本化客户端——{@link UnderTestClient} 模拟被测模型
 * （按问题返回预设答案，其中一例故意答错以演示失败用例隔离），{@link ScriptedJudgeClient}
 * 模拟裁判模型（从裁判提示词中抽取「参考答案 / 实际输出」，按是否包含给出 YES/NO）。
 * 全程零真实网络、无需 API Key。生产环境把这两个 fake 换成任意平台客户端即可。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalsDemo {

	private EvalsDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		// 1. 构造数据集（也可从 CSV/JSONL 加载：EvalUtil.csvLoader().load(path)）
		EvalDataset dataset = EvalDataset.of("qa-demo", List.of(
				EvalCase.of("c1", "法国首都是哪里？", "巴黎"),
				EvalCase.of("c2", "1+1等于几？", "2"),
				EvalCase.of("c3", "用一句话介绍 Java", "Java 是一门面向对象的编程语言。")));

		AiClient underTest = new UnderTestClient();
		AiClient judge = new ScriptedJudgeClient();

		// 2. 装配指标：确定性精确匹配 + LLM 裁判（BINARY 模式）
		ExactMatchMetric exact = ExactMatchMetric.builder().build();
		LlmJudgeMetric judgeMetric = LlmJudgeMetric.builder(judge, "judge-model")
				.mode(JudgeMode.BINARY)
				.rubric("答案是否正确、简洁")
				.passThreshold(0.6)
				.build();

		// 3. 跑评估（try-with-resources 自动关闭 runner）
		EvalReport report;
		try (EvalRunner runner = EvalRunner.builder(underTest, "under-test-model")
				.addMetric(exact)
				.addMetric(judgeMetric)
				.build()) {
			report = runner.run(dataset);
		}

		// 4. 渲染 Markdown 报告并打印
		System.out.println("===== 评估报告（Markdown） =====");
		System.out.println(new EvalMarkdownReportWriter().render(report));

		// 5. JSON 报告落盘（NaN 序列化为 JSON null，便于下游程序消费）
		Path json = Path.of("target", "evals-demo-report.json");
		new EvalJsonReportWriter().write(report, json);
		System.out.println("JSON 报告已写出: " + json.toAbsolutePath());

		// 6. CI 阈值断言：不达标抛 AssertionError（这里阈值宽松，必然通过）
		EvalAssertions.assertMeets(report, EvalThreshold.builder()
				.putMean("exact_match", 0.5)
				.putPassRate("llm_judge", 0.5)
				.build());
		System.out.println("阈值断言通过：exact_match 均值 ≥ 0.5，llm_judge 通过率 ≥ 0.5");
	}

	/**
	 * 离线被测模型：按用户问题返回预设答案（c2 故意答错，演示失败用例不中断整批）。
	 */
	private static final class UnderTestClient implements AiClient {
		@Override
		public String name() {
			return "under-test-fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			String question = request.messages().get(request.messages().size() - 1).content();
			String answer;
			if (question.contains("法国")) {
				answer = "巴黎";
			} else if (question.contains("1+1")) {
				answer = "3";
			} else {
				answer = "Java 是一门面向对象的编程语言。";
			}
			return ChatResponse.of("demo-resp", request.model(),
					List.of(Choice.of(0, ChatMessage.assistant(answer), "stop")),
					TokenUsage.of(6, 12, 18), null);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}

	/**
	 * 离线裁判模型：从 BINARY 提示词中抽取「参考答案 / 实际输出」，实际输出包含参考答案判 YES。
	 */
	private static final class ScriptedJudgeClient implements AiClient {
		@Override
		public String name() {
			return "judge-fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			String prompt = request.messages().get(0).content();
			String expected = between(prompt, "【参考答案】", "\n【实际输出】");
			String actual = between(prompt, "【实际输出】", "\n只回答");
			String verdict = actual.contains(expected.trim()) ? "YES" : "NO";
			return ChatResponse.of("judge-resp", request.model(),
					List.of(Choice.of(0, ChatMessage.assistant(verdict), "stop")),
					TokenUsage.of(20, 3, 23), null);
		}

		private static String between(String text, String start, String end) {
			int s = text.indexOf(start);
			if (s < 0) {
				return "";
			}
			s += start.length();
			int e = text.indexOf(end, s);
			return (e < 0 ? text.substring(s) : text.substring(s, e)).trim();
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}
}
