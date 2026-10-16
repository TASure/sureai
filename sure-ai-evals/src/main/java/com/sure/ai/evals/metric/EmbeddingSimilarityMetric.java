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

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.tool.lang.Assert;

/**
 * 语义相似度指标：用 {@link EmbeddingClient} 计算期望输出与实际输出的余弦相似度。
 *
 * <p>得分即余弦相似度（clamp 到 [0,1]），通过线即相似度阈值，默认 0.8（可配）。
 * 任何异常均不向上抛出：缺期望输出、embedding 调用失败 / 返回空向量、零范数向量，
 * 一律返回 {@link MetricResult#notApplicable(String)} 并在理由中说明。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EmbeddingSimilarityMetric implements EvalMetric {

	/** 默认通过阈值（余弦相似度 ≥ 0.8 视为语义等价）。 */
	public static final double DEFAULT_THRESHOLD = 0.8;

	private final EmbeddingClient embeddingClient;
	private final String embeddingModel;
	private final double threshold;
	private final String name;

	private EmbeddingSimilarityMetric(Builder b) {
		this.embeddingClient = b.embeddingClient;
		this.embeddingModel = b.embeddingModel;
		this.threshold = b.threshold;
		this.name = b.name;
	}

	/**
	 * 创建 Builder。
	 *
	 * @param embeddingClient 向量客户端
	 * @param embeddingModel  向量模型名
	 * @return Builder
	 */
	public static Builder builder(EmbeddingClient embeddingClient, String embeddingModel) {
		Assert.notNull(embeddingClient, "embeddingClient 不能为 null");
		Assert.notBlank(embeddingModel, "embeddingModel 不能为空白");
		return new Builder().embeddingClient(embeddingClient).embeddingModel(embeddingModel);
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public double passThreshold() {
		return this.threshold;
	}

	@Override
	public MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context) {
		String expected = evalCase.expected();
		if (expected == null) {
			return MetricResult.notApplicable("缺期望输出");
		}
		String actual = outcome.actualText();
		if (actual.isEmpty()) {
			return MetricResult.notApplicable("实际输出为空");
		}
		try {
			float[] a = embed(expected);
			float[] b = embed(actual);
			if (a == null || b == null || a.length == 0 || b.length == 0) {
				return MetricResult.notApplicable("向量返回为空");
			}
			double similarity = cosine(a, b);
			if (Double.isNaN(similarity)) {
				return MetricResult.notApplicable("零范数向量，余弦无定义");
			}
			return MetricResult.of(similarity, String.format("余弦相似度 %.4f", similarity));
		} catch (RuntimeException e) {
			return MetricResult.notApplicable("embedding 调用失败: " + e.getMessage());
		}
	}

	private float[] embed(String text) {
		EmbeddingResponse response = this.embeddingClient.embed(this.embeddingModel, text);
		if (response == null || response.embeddings().isEmpty()) {
			return null;
		}
		return response.embeddings().get(0);
	}

	private static double cosine(float[] a, float[] b) {
		int n = Math.min(a.length, b.length);
		double dot = 0.0;
		double na = 0.0;
		double nb = 0.0;
		for (int i = 0; i < n; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		if (na == 0.0 || nb == 0.0) {
			return Double.NaN;
		}
		return dot / (Math.sqrt(na) * Math.sqrt(nb));
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private EmbeddingClient embeddingClient;
		private String embeddingModel;
		private double threshold = DEFAULT_THRESHOLD;
		private String name = "embedding_similarity";

		private Builder() {
		}

		private Builder embeddingClient(EmbeddingClient embeddingClient) {
			this.embeddingClient = embeddingClient;
			return this;
		}

		private Builder embeddingModel(String embeddingModel) {
			this.embeddingModel = embeddingModel;
			return this;
		}

		/**
		 * 设置相似度通过阈值（默认 0.8）。
		 *
		 * @param threshold 阈值 [0,1]
		 * @return this
		 */
		public Builder threshold(double threshold) {
			this.threshold = threshold;
			return this;
		}

		/**
		 * 设置指标名（默认 {@code embedding_similarity}）。
		 *
		 * @param name 指标名
		 * @return this
		 */
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public EmbeddingSimilarityMetric build() {
			Assert.notNull(this.embeddingClient, "embeddingClient 不能为 null");
			Assert.notBlank(this.embeddingModel, "embeddingModel 不能为空白");
			return new EmbeddingSimilarityMetric(this);
		}
	}
}
