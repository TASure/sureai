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
package com.sure.ai.rag.evaluation;

import java.util.List;

import com.sure.ai.client.AiClient;

/**
 * 上下文召回率（context recall）：衡量参考答案中的要点是否被检索上下文覆盖。
 *
 * <p>口径（RAGAS）：</p>
 * <ol>
 *   <li>让 LLM 从参考答案（ground truth）中抽取应被回答覆盖的全部要点（每行一条）；</li>
 *   <li>逐点让 LLM 判断该要点是否被任一上下文覆盖（YES/NO）；</li>
 *   <li>{@code context_recall = 被覆盖要点数 / 要点总数}。</li>
 * </ol>
 *
 * <p>不适用约定：轨迹未提供参考答案（{@code referenceAnswer} 为 null/空白）时返回
 * {@link Double#NaN}，由 {@link RagEvaluator} 归入 notApplicable，不计入均值。
 * 有参考答案但未能抽出要点时同样返回 NaN（视为无法判定）。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class ContextRecallMetric extends LlmJudge implements RagMetric {

	/** 要点抽取提示词模板。 */
	public static final String POINT_EXTRACTION_PROMPT =
			"请从下面的参考答案中列出回答该问题所必须覆盖的全部要点，每条一行，"
			+ "不要编号、不要解释：\n参考答案：{reference}";

	/** 要点覆盖判定提示词模板。 */
	public static final String COVERAGE_JUDGE_PROMPT =
			"请判断下面这个要点是否已被给定的上下文所覆盖（任一上下文包含即可）。只回答 YES 或 NO。\n"
			+ "要点：{point}\n上下文：\n{contexts}";

	private static final String NAME = "context_recall";

	private ContextRecallMetric(Builder builder) {
		super(builder.chatClient, builder.model, builder.maxTokens);
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public double evaluate(RagTrace trace) {
		String reference = trace.referenceAnswer();
		if (reference == null || reference.isBlank()) {
			return Double.NaN;
		}
		List<String> points = LlmJudge.parseLineItems(ask(
				POINT_EXTRACTION_PROMPT.replace("{reference}", reference)));
		if (points.isEmpty()) {
			return Double.NaN;
		}
		String contextText = FaithfulnessMetric.formatContexts(trace.contexts());
		int covered = 0;
		for (String point : points) {
			String prompt = COVERAGE_JUDGE_PROMPT
					.replace("{point}", point)
					.replace("{contexts}", contextText);
			if (LlmJudge.parseYesNo(ask(prompt), false)) {
				covered++;
			}
		}
		return (double) covered / points.size();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private AiClient chatClient;
		private String model;
		private int maxTokens;

		private Builder() {
		}

		/**
		 * 设置对话客户端。
		 *
		 * @param chatClient 对话客户端
		 * @return this
		 */
		public Builder chatClient(AiClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		/**
		 * 设置模型名。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 设置 maxTokens。
		 *
		 * @param maxTokens 最大 token 数
		 * @return this
		 */
		public Builder maxTokens(int maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public ContextRecallMetric build() {
			return new ContextRecallMetric(this);
		}
	}
}
