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

package com.sure.ai.rag.retriever;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.rag.Reranker;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.store.VectorStore;
import com.sure.tool.lang.Assert;

/**
 * 向量检索器：查询向量化 → 向量库相似度检索 → （可选）重排 → 文档化。
 *
 * @author sureai
 * @since 0.2.0
 */
public class VectorRetriever implements Retriever {

	private final VectorStore store;
	private final EmbeddingProvider embeddingProvider;
	private final double minScore;
	private final Reranker reranker;

	/**
	 * 创建向量检索器。
	 *
	 * @param store 向量存储
	 * @param embeddingProvider 向量化实现
	 */
	public VectorRetriever(VectorStore store, EmbeddingProvider embeddingProvider) {
		this(store, embeddingProvider, Double.NEGATIVE_INFINITY, null);
	}

	/**
	 * 创建带相似度阈值的向量检索器。
	 *
	 * @param store 向量存储
	 * @param embeddingProvider 向量化实现
	 * @param minScore 最低相似度阈值（含），低于该值的条目被过滤
	 */
	public VectorRetriever(VectorStore store, EmbeddingProvider embeddingProvider, double minScore) {
		this(store, embeddingProvider, minScore, null);
	}

	/**
	 * 全参构造器。
	 *
	 * @param store 向量存储
	 * @param embeddingProvider 向量化实现
	 * @param minScore 最低相似度阈值
	 * @param reranker 可选重排器，null 表示不重排
	 */
	public VectorRetriever(VectorStore store, EmbeddingProvider embeddingProvider,
			double minScore, Reranker reranker) {
		this.store = Assert.notNull(store, "store 不能为 null");
		this.embeddingProvider = Assert.notNull(embeddingProvider, "embeddingProvider 不能为 null");
		this.minScore = minScore;
		this.reranker = reranker;
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
	public List<Document> retrieve(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		List<SimilaritySearchResult> results = retrieveWithScores(query, topK);
		List<Document> documents = new ArrayList<>(results.size());
		for (SimilaritySearchResult result : results) {
			documents.add(Document.of(result.id(), result.text(), result.metadata()));
		}
		return documents;
	}

	/**
	 * 检索并保留相似度得分；配置了重排器时先向量召回再重排。
	 *
	 * @param query 用户查询
	 * @param topK 返回条数，必须大于 0
	 * @return 带得分的检索结果（按最终得分降序）
	 */
	public List<SimilaritySearchResult> retrieveWithScores(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		float[] queryEmbedding = embeddingProvider.embed(query);
		List<SimilaritySearchResult> results = store.similaritySearch(queryEmbedding, topK, minScore);
		if (reranker != null) {
			results = reranker.rerank(query, results);
		}
		return results;
	}

	/**
	 * 返回重排器（可能为 null）。
	 *
	 * @return 重排器
	 */
	public Reranker reranker() {
		return this.reranker;
	}

	/**
	 * VectorRetriever 构造器。
	 */
	public static final class Builder {

		private VectorStore store;
		private EmbeddingProvider embeddingProvider;
		private double minScore = Double.NEGATIVE_INFINITY;
		private Reranker reranker;

		private Builder() {
		}

		/**
		 * 设置向量存储。
		 *
		 * @param store 向量存储
		 * @return this
		 */
		public Builder store(VectorStore store) {
			this.store = store;
			return this;
		}

		/**
		 * 设置向量化实现。
		 *
		 * @param embeddingProvider 向量化实现
		 * @return this
		 */
		public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
			this.embeddingProvider = embeddingProvider;
			return this;
		}

		/**
		 * 设置最低相似度阈值。
		 *
		 * @param minScore 阈值
		 * @return this
		 */
		public Builder minScore(double minScore) {
			this.minScore = minScore;
			return this;
		}

		/**
		 * 设置重排器（可选）。
		 *
		 * @param reranker 重排器
		 * @return this
		 */
		public Builder reranker(Reranker reranker) {
			this.reranker = reranker;
			return this;
		}

		/**
		 * 构建检索器。
		 *
		 * @return 向量检索器
		 */
		public VectorRetriever build() {
			Assert.notNull(this.store, "store 不能为 null");
			Assert.notNull(this.embeddingProvider, "embeddingProvider 不能为 null");
			return new VectorRetriever(this.store, this.embeddingProvider, this.minScore, this.reranker);
		}
	}
}
