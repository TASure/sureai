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
import com.sure.ai.rag.model.Document;

/**
 * 上下文精确率（context precision）：衡量相关上下文是否排在检索结果前列。
 *
 * <p>口径（RAGAS）：对每个上下文（保持检索排序）让 LLM 判断对问题是否相关（YES/NO），
 * 再按排序加权：</p>
 * <pre>
 * precision@k = (前 k 个中相关的个数) / k
 * context_precision = Σ(precision@k · 相关_k) / 相关总数
 * </pre>
 *
 * <p>其中 {@code 相关_k} 表示第 k 个上下文本身是否相关。该指标对排序敏感：
 * 相关文档越靠前，得分越高；全部不相关时返回 {@code 0.0}。</p>
 *
 * <p>容错：单条相关性解析失败保守判为不相关（false）。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class ContextPrecisionMetric extends LlmJudge implements RagMetric {

	/** 相关性判定提示词模板。 */
	public static final String RELEVANCE_PROMPT =
			"请判断下面这段上下文对回答用户问题是否相关、是否包含有用信息。只回答 YES 或 NO。\n"
			+ "问题：{question}\n上下文：{context}";

	private static final String NAME = "context_precision";

	private ContextPrecisionMetric(Builder builder) {
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
		List<Document> contexts = trace.contexts();
		if (contexts.isEmpty()) {
			return 0.0;
		}
		int relevantCount = 0;
		int relevantSoFar = 0;
		double weightedSum = 0.0;
		for (int k = 0; k < contexts.size(); k++) {
			String prompt = RELEVANCE_PROMPT
					.replace("{question}", trace.question())
					.replace("{context}", contexts.get(k).text());
			// 解析失败保守判为不相关
			boolean relevant = LlmJudge.parseYesNo(ask(prompt), false);
			if (relevant) {
				relevantCount++;
				relevantSoFar++;
				double precisionAtK = (double) relevantSoFar / (k + 1);
				weightedSum += precisionAtK;
			}
		}
		if (relevantCount == 0) {
			return 0.0;
		}
		return weightedSum / relevantCount;
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
		public ContextPrecisionMetric build() {
			return new ContextPrecisionMetric(this);
		}
	}
}
