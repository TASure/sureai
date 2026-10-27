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
package com.sure.ai.rag.splitter;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.tool.lang.Assert;

/**
 * 语义分块器（Semantic Chunking）：按语义边界切分，而非固定字符数。
 *
 * <p>核心思路：文本在主题切换处相邻句子的语义相似度会骤降。本实现：</p>
 * <ol>
 *   <li>先按中英文句末标点（{@code .!?。！？；;} 与换行）切分为句子序列；</li>
 *   <li>用 {@link EmbeddingProvider} 逐句向量化；</li>
 *   <li>计算相邻句子的余弦相似度；</li>
 *   <li>当相邻相似度低于阈值时在该处断开，把连续高相似度的句子合并为一个块。</li>
 * </ol>
 *
 * <p>断点阈值两种模式（a3 决策）：</p>
 * <ul>
 *   <li><b>自适应阈值（默认）</b>：{@code threshold = 均值 - 标准差}——落在分布的较低
 *       一侧，只在真正的语义陡降处切分，无需人工调参；</li>
 *   <li><b>固定阈值</b>：通过 {@link Builder#threshold(double)} 显式指定余弦阈值
 *       （范围 [-1,1]），高于该值则视为同一块。</li>
 * </ul>
 *
 * <p>容错：embedding 调用抛异常时退化为 {@link FixedSizeTextSplitter}（块大小 1000、
 * 重叠 100），绝不向上抛出。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class SemanticTextSplitter implements TextSplitter {

	/** 句末断句正则的后行断言：保留标点到前一句。 */
	private static final String SENTENCE_BOUNDARY = "(?<=[.!?。！？；;\\n])\\s*";

	/** embedding 失败时降级用的固定块大小。 */
	public static final int FALLBACK_CHUNK_SIZE = 1000;
	/** embedding 失败时降级用的块重叠。 */
	public static final int FALLBACK_CHUNK_OVERLAP = 100;

	private final EmbeddingProvider embeddingProvider;
	private final Double threshold;

	private SemanticTextSplitter(Builder builder) {
		this.embeddingProvider = builder.embeddingProvider;
		this.threshold = builder.threshold;
	}

	/**
	 * 使用自适应阈值（均值 - 标准差）创建。
	 *
	 * @param embeddingProvider 向量化实现
	 * @return 语义分块器
	 */
	public static SemanticTextSplitter createDefault(EmbeddingProvider embeddingProvider) {
		return builder().embeddingProvider(embeddingProvider).build();
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
	public List<String> split(String text) {
		if (text == null || text.isEmpty()) {
			return new ArrayList<>(0);
		}
		List<String> sentences = splitSentences(text);
		if (sentences.size() <= 1) {
			return new ArrayList<>(sentences);
		}

		List<float[]> vectors;
		try {
			vectors = this.embeddingProvider.embedAll(sentences);
		} catch (RuntimeException e) {
			// embedding 失败：降级为固定大小分块
			return new FixedSizeTextSplitter(FALLBACK_CHUNK_SIZE, FALLBACK_CHUNK_OVERLAP).split(text);
		}
		if (vectors == null || vectors.size() != sentences.size()) {
			return new FixedSizeTextSplitter(FALLBACK_CHUNK_SIZE, FALLBACK_CHUNK_OVERLAP).split(text);
		}

		// 计算相邻相似度
		int n = sentences.size();
		double[] sims = new double[n - 1];
		for (int i = 0; i < n - 1; i++) {
			sims[i] = cosineSimilarity(vectors.get(i), vectors.get(i + 1));
		}

		double cutThreshold = resolveThreshold(sims);

		// 按阈值切分：累积句子，遇到相似度 < 阈值则断开
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder(sentences.get(0));
		for (int i = 0; i < sims.length; i++) {
			if (sims[i] < cutThreshold) {
				chunks.add(current.toString().trim());
				current = new StringBuilder(sentences.get(i + 1));
			} else {
				current.append(sentences.get(i + 1));
			}
		}
		chunks.add(current.toString().trim());

		// 剔除空白块
		List<String> result = new ArrayList<>(chunks.size());
		for (String chunk : chunks) {
			if (!chunk.isEmpty()) {
				result.add(chunk);
			}
		}
		return result;
	}

	/**
	 * 切分为句子序列（过滤空句）。
	 *
	 * @param text 原始文本
	 * @return 句子列表
	 */
	private static List<String> splitSentences(String text) {
		String[] raw = text.split(SENTENCE_BOUNDARY);
		List<String> sentences = new ArrayList<>(raw.length);
		for (String s : raw) {
			String trimmed = s == null ? "" : s.trim();
			if (!trimmed.isEmpty()) {
				sentences.add(trimmed);
			}
		}
		return sentences;
	}

	/**
	 * 解析断点阈值：固定阈值优先；否则自适应为均值减标准差。
	 *
	 * @param sims 相邻相似度数组
	 * @return 断点阈值
	 */
	private double resolveThreshold(double[] sims) {
		if (this.threshold != null) {
			return this.threshold;
		}
		double sum = 0;
		for (double s : sims) {
			sum += s;
		}
		double mean = sum / sims.length;
		double sq = 0;
		for (double s : sims) {
			sq += (s - mean) * (s - mean);
		}
		double std = Math.sqrt(sq / sims.length);
		return mean - std;
	}

	/**
	 * 余弦相似度。
	 *
	 * @param a 向量 a
	 * @param b 向量 b
	 * @return 余弦相似度 [-1,1]，零向量返回 0
	 */
	private static double cosineSimilarity(float[] a, float[] b) {
		int len = Math.min(a.length, b.length);
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < len; i++) {
			dot += (double) a[i] * b[i];
			normA += (double) a[i] * a[i];
			normB += (double) b[i] * b[i];
		}
		if (normA == 0 || normB == 0) {
			return 0;
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}

	/**
	 * SemanticTextSplitter 构造器。
	 */
	public static final class Builder {

		private EmbeddingProvider embeddingProvider;
		private Double threshold;

		private Builder() {
		}

		/**
		 * 设置向量化实现（必填）。
		 *
		 * @param embeddingProvider 向量化实现
		 * @return this
		 */
		public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
			this.embeddingProvider = embeddingProvider;
			return this;
		}

		/**
		 * 显式设置固定余弦阈值；不设置则使用自适应阈值（均值 - 标准差）。
		 *
		 * @param threshold 断点阈值，范围 [-1,1]
		 * @return this
		 */
		public Builder threshold(double threshold) {
			Assert.isTrue(threshold >= -1.0 && threshold <= 1.0,
					"threshold 必须位于 [-1,1]，实际为 {}", threshold);
			this.threshold = threshold;
			return this;
		}

		/**
		 * 构建语义分块器。
		 *
		 * @return 分块器
		 */
		public SemanticTextSplitter build() {
			Assert.notNull(this.embeddingProvider, "embeddingProvider 不能为 null");
			return new SemanticTextSplitter(this);
		}
	}
}
