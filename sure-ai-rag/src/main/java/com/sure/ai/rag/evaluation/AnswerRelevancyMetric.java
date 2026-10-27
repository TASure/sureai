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
import com.sure.ai.rag.embedding.EmbeddingProvider;

/**
 * 答案相关性（answer relevancy）：衡量答案是否切题。
 *
 * <p>支持两种口径：</p>
 * <ul>
 *   <li><b>默认（LLM 直接评分）</b>：未注入 {@link EmbeddingProvider} 时，让 LLM 对
 *       「答案与问题的相关度」直接打 0~1 分；</li>
 *   <li><b>Embedding 增强（RAGAS 口径）</b>：注入 {@link EmbeddingProvider} 后，让 LLM
 *       从答案反推若干可能的问题，再计算「反推问题向量」与「原问题向量」的平均余弦相似度。
 *       反推问题为空时回退为 LLM 直接评分。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class AnswerRelevancyMetric extends LlmJudge implements RagMetric {

	/** LLM 直接评分提示词模板。 */
	public static final String DIRECT_SCORE_PROMPT =
			"请评估下面答案与问题的相关程度，给出 0 到 1 之间的分数"
			+ "（1 表示答案完全切题，0 表示答案与问题无关），只输出数字：\n"
			+ "问题：{question}\n答案：{answer}";

	/** 反推问题提示词模板。 */
	public static final String REVERSE_QUESTION_PROMPT =
			"请根据下面的答案反推它可能在回答哪些用户问题，每行一个问题，"
			+ "不要编号、不要解释：\n答案：{answer}";

	private static final String NAME = "answer_relevancy";

	private final EmbeddingProvider embeddingProvider;

	private AnswerRelevancyMetric(Builder builder) {
		super(builder.chatClient, builder.model, builder.maxTokens);
		this.embeddingProvider = builder.embeddingProvider;
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
		if (this.embeddingProvider != null) {
			double embeddingScore = evaluateByEmbedding(trace);
			if (!Double.isNaN(embeddingScore)) {
				return embeddingScore;
			}
		}
		return evaluateByLlmScore(trace);
	}

	private double evaluateByLlmScore(RagTrace trace) {
		String prompt = DIRECT_SCORE_PROMPT
				.replace("{question}", trace.question())
				.replace("{answer}", trace.answer());
		// 解析失败保守给 0.5（中性）
		return LlmJudge.parseScore01(ask(prompt), 0.5);
	}

	private double evaluateByEmbedding(RagTrace trace) {
		List<String> reverseQuestions = LlmJudge.parseLineItems(ask(
				REVERSE_QUESTION_PROMPT.replace("{answer}", trace.answer())));
		if (reverseQuestions.isEmpty()) {
			return Double.NaN;
		}
		try {
			float[] original = this.embeddingProvider.embed(trace.question());
			List<float[]> reverseVecs = this.embeddingProvider.embedAll(reverseQuestions);
			if (original == null || reverseVecs.isEmpty()) {
				return Double.NaN;
			}
			double sum = 0.0;
			int n = 0;
			for (float[] vec : reverseVecs) {
				double sim = cosineSimilarity(original, vec);
				if (!Double.isNaN(sim)) {
					sum += sim;
					n++;
				}
			}
			if (n == 0) {
				return Double.NaN;
			}
			double avg = sum / n;
			return Math.max(0.0, Math.min(1.0, avg));
		} catch (RuntimeException e) {
			return Double.NaN;
		}
	}

	/**
	 * 余弦相似度。
	 *
	 * @param a 向量 a
	 * @param b 向量 b
	 * @return 相似度，零向量返回 NaN
	 */
	static double cosineSimilarity(float[] a, float[] b) {
		if (a == null || b == null || a.length == 0 || a.length != b.length) {
			return Double.NaN;
		}
		double dot = 0.0;
		double normA = 0.0;
		double normB = 0.0;
		for (int i = 0; i < a.length; i++) {
			dot += (double) a[i] * b[i];
			normA += (double) a[i] * a[i];
			normB += (double) b[i] * b[i];
		}
		if (normA == 0.0 || normB == 0.0) {
			return Double.NaN;
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private AiClient chatClient;
		private String model;
		private int maxTokens;
		private EmbeddingProvider embeddingProvider;

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
		 * 可选：注入向量化提供者，启用 RAGAS embedding 口径的答案相关性。
		 *
		 * @param embeddingProvider 向量化提供者
		 * @return this
		 */
		public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
			this.embeddingProvider = embeddingProvider;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public AnswerRelevancyMetric build() {
			return new AnswerRelevancyMetric(this);
		}
	}
}
