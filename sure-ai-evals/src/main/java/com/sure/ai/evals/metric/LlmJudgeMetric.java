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

package com.sure.ai.evals.metric;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sure.ai.client.AiClient;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.tool.lang.Assert;

/**
 * 通用 LLM-as-judge 指标：调用裁判模型对被测输出打分。
 *
 * <p>支持四种模式（{@link JudgeMode}）：BINARY 二元 rubric、SCALE_1_TO_5 分量表、
 * SCORE_100 百分制、PAIRWISE 与参考答案成对对比。rubric 提示词模板可自定义，占位符
 * {@code {input}} / {@code {expected}} / {@code {actual}} 渲染时替换。</p>
 *
 * <p><b>容错原则（绝不向上抛出）</b>：裁判调用抛异常、返回空文本、输出无法解析时，
 * 默认返回 {@link MetricResult#notApplicable(String)}（NaN，聚合剔除）；可通过
 * {@link Builder#onUnparseableDefaultScore(double)} 改为返回可复现的固定默认分。</p>
 *
 * <p>归一化口径：二元 yes→1.0 / no→0.0；1~5→{@code (n-1)/4}；百分制→{@code n/100}；
 * pairwise→A 胜 1.0 / 平 0.5 / B 胜 0.0。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class LlmJudgeMetric implements EvalMetric {

	private static final Pattern YES_WORD = Pattern.compile("\\byes\\b");
	private static final Pattern NO_WORD = Pattern.compile("\\bno\\b");
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

	/** 默认最大生成 token 数。 */
	public static final int DEFAULT_MAX_TOKENS = 512;

	private static final String DEFAULT_BINARY_RUBRIC = """
		你是严格的质量评审。请判断「实际输出」是否满足如下要求：
		{rubric}
		【问题】{input}
		【参考答案】{expected}
		【实际输出】{actual}
		只回答 YES 或 NO。""";

	private static final String DEFAULT_SCALE_RUBRIC = """
		你是严格的质量评审。请按如下标准给「实际输出」打分（1~5 整数）：
		{rubric}
		【问题】{input}
		【参考答案】{expected}
		【实际输出】{actual}
		只回答一个 1~5 的整数。""";

	private static final String DEFAULT_PAIRWISE_RUBRIC = """
		请比较两份答案哪个更好（A 为实际输出，B 为参考答案）。
		评判标准：{rubric}
		【问题】{input}
		【答案A】{actual}
		【答案B】{expected}
		只回答 A、B 或 TIE。""";

	private final AiClient judgeClient;
	private final String judgeModel;
	private final JudgeMode mode;
	private final String rubric;
	private final int maxTokens;
	private final String name;
	private final double passThreshold;
	private final boolean unparseableIsNa;
	private final double unparseableScore;

	private LlmJudgeMetric(Builder b) {
		this.judgeClient = b.judgeClient;
		this.judgeModel = b.judgeModel;
		this.mode = b.mode;
		this.rubric = b.rubric;
		this.maxTokens = b.maxTokens <= 0 ? DEFAULT_MAX_TOKENS : b.maxTokens;
		this.name = b.name;
		this.passThreshold = b.passThreshold;
		this.unparseableIsNa = b.unparseableIsNa;
		this.unparseableScore = b.unparseableScore;
	}

	/**
	 * 创建 Builder。
	 *
	 * @param judgeClient 裁判对话客户端
	 * @param judgeModel  裁判模型名
	 * @return Builder
	 */
	public static Builder builder(AiClient judgeClient, String judgeModel) {
		Assert.notNull(judgeClient, "judgeClient 不能为 null");
		Assert.notBlank(judgeModel, "judgeModel 不能为空白");
		return new Builder().judgeClient(judgeClient).judgeModel(judgeModel);
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public double passThreshold() {
		return this.passThreshold;
	}

	@Override
	public MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context) {
		String actual = outcome.actualText();
		String expected = evalCase.expected() == null ? "" : evalCase.expected();
		String prompt = render(evalCase.inputText(), expected, actual);
		String judge = ask(prompt);
		if (judge == null) {
			return failSafe("裁判模型调用失败或返回空文本");
		}
		return switch (this.mode) {
			case BINARY -> parseBinary(judge);
			case SCALE_1_TO_5 -> parseScale(judge);
			case SCORE_100 -> parseScore100(judge);
			case PAIRWISE -> parsePairwise(judge);
		};
	}

	private MetricResult failSafe(String reason) {
		if (this.unparseableIsNa) {
			return MetricResult.notApplicable(reason);
		}
		return MetricResult.of(this.unparseableScore, reason + "，按默认分 " + this.unparseableScore);
	}

	private MetricResult parseBinary(String judge) {
		String t = judge.toLowerCase(Locale.ROOT);
		boolean no = t.contains("不支持") || t.contains("不相关") || t.contains("否")
			|| NO_WORD.matcher(t).find();
		boolean yes = t.contains("支持") || t.contains("相关") || t.contains("是")
			|| YES_WORD.matcher(t).find();
		if (no == yes) {
			return failSafe("裁判输出无法判定 yes/no: " + snippet(judge));
		}
		return no ? MetricResult.fail("裁判判定 NO: " + snippet(judge))
			: MetricResult.pass("裁判判定 YES: " + snippet(judge));
	}

	private MetricResult parseScale(String judge) {
		Double n = extractNumber(judge);
		if (n == null || n < 1.0 || n > 5.0) {
			return failSafe("裁判输出不在 1~5 范围: " + snippet(judge));
		}
		double score = (n - 1.0) / 4.0;
		return MetricResult.of(score, "裁判打分 " + n.intValue() + "/5");
	}

	private MetricResult parseScore100(String judge) {
		Double n = extractNumber(judge);
		if (n == null || n < 0.0 || n > 100.0) {
			return failSafe("裁判输出不在 0~100 范围: " + snippet(judge));
		}
		double score = n <= 1.0 ? n : n / 100.0;
		return MetricResult.of(score, "裁判打分 " + n + "/100");
	}

	private MetricResult parsePairwise(String judge) {
		String t = judge.trim().toUpperCase(Locale.ROOT);
		boolean a = t.startsWith("A") || t.contains("答案A") || t.contains("实际输出更好");
		boolean b = t.startsWith("B") || t.contains("答案B") || t.contains("参考答案更好");
		if (a == b) {
			if (t.contains("TIE") || t.contains("平")) {
				return MetricResult.of(0.5, "裁判判定平局: " + snippet(judge));
			}
			return failSafe("裁判输出无法判定 A/B/TIE: " + snippet(judge));
		}
		return a ? MetricResult.pass("裁判判定实际输出(A)更好: " + snippet(judge))
			: MetricResult.of(0.0, "裁判判定参考答案(B)更好: " + snippet(judge));
	}

	private static Double extractNumber(String text) {
		Matcher m = NUMBER.matcher(text);
		if (!m.find()) {
			return null;
		}
		try {
			return Double.parseDouble(m.group());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String snippet(String text) {
		String t = text.strip();
		return t.length() > 120 ? t.substring(0, 120) + "…" : t;
	}

	private String render(String input, String expected, String actual) {
		String template = switch (this.mode) {
			case BINARY -> DEFAULT_BINARY_RUBRIC;
			case SCALE_1_TO_5 -> DEFAULT_SCALE_RUBRIC;
			case SCORE_100 -> DEFAULT_SCALE_RUBRIC;
			case PAIRWISE -> DEFAULT_PAIRWISE_RUBRIC;
		};
		if (!this.rubric.isBlank()) {
			template = template.replace("{rubric}", this.rubric);
		} else {
			template = template.replace("{rubric}", "（无额外标准，按正确性与相关性判断）");
		}
		return template
			.replace("{input}", input)
			.replace("{expected}", expected)
			.replace("{actual}", actual);
	}

	private String ask(String prompt) {
		try {
			ChatRequest request = ChatRequest.builder()
				.model(this.judgeModel)
				.messages(List.of(ChatMessage.user(prompt)))
				.maxTokens(this.maxTokens)
				.build();
			ChatResponse response = this.judgeClient.chat(request);
			String text = response == null ? null : response.firstText();
			if (text == null || text.isBlank()) {
				return null;
			}
			return text.trim();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private AiClient judgeClient;
		private String judgeModel;
		private JudgeMode mode = JudgeMode.BINARY;
		private String rubric = "";
		private int maxTokens = DEFAULT_MAX_TOKENS;
		private String name = "llm_judge";
		private double passThreshold = 0.6;
		private boolean unparseableIsNa = true;
		private double unparseableScore = 0.0;

		private Builder() {
		}

		/**
		 * 设置裁判客户端。
		 *
		 * @param judgeClient 客户端
		 * @return this
		 */
		public Builder judgeClient(AiClient judgeClient) {
			this.judgeClient = judgeClient;
			return this;
		}

		/**
		 * 设置裁判模型名。
		 *
		 * @param judgeModel 模型名
		 * @return this
		 */
		public Builder judgeModel(String judgeModel) {
			this.judgeModel = judgeModel;
			return this;
		}

		/**
		 * 设置评分模式（默认 BINARY）。
		 *
		 * @param mode 模式
		 * @return this
		 */
		public Builder mode(JudgeMode mode) {
			this.mode = mode;
			return this;
		}

		/**
		 * 设置自定义 rubric 文本（替换模板中的 {rubric} 占位符）。
		 *
		 * @param rubric rubric 文本
		 * @return this
		 */
		public Builder rubric(String rubric) {
			this.rubric = rubric;
			return this;
		}

		/**
		 * 设置最大生成 token 数（默认 512）。
		 *
		 * @param maxTokens token 数
		 * @return this
		 */
		public Builder maxTokens(int maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		/**
		 * 设置指标名（默认 {@code llm_judge}）。
		 *
		 * @param name 指标名
		 * @return this
		 */
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		/**
		 * 设置通过阈值（默认 0.6；二元模式可设 1.0）。
		 *
		 * @param passThreshold 阈值 [0,1]
		 * @return this
		 */
		public Builder passThreshold(double passThreshold) {
			this.passThreshold = passThreshold;
			return this;
		}

		/**
		 * 解析失败 / 模型异常时返回可复现的固定默认分（默认行为是返回 notApplicable/NaN）。
		 *
		 * @param score 默认分 [0,1]
		 * @return this
		 */
		public Builder onUnparseableDefaultScore(double score) {
			this.unparseableIsNa = false;
			this.unparseableScore = score;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public LlmJudgeMetric build() {
			Assert.notNull(this.judgeClient, "judgeClient 不能为 null");
			Assert.notBlank(this.judgeModel, "judgeModel 不能为空白");
			return new LlmJudgeMetric(this);
		}
	}
}
