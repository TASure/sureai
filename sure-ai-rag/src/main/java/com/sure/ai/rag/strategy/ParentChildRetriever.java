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
package com.sure.ai.rag.strategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.splitter.ParentChildSplitter;
import com.sure.ai.rag.store.VectorStore;
import com.sure.tool.lang.Assert;

/**
 * 父子检索器（Small-to-Big）：用子块做向量召回，命中后按 {@code parentId} 聚合返回
 * <b>父块全文</b>。
 *
 * <p>检索时实际查询的是 {@code childStore}（子块向量），但最终返回的文档正文是父块——
 * 这样既利用了小子块语义集中、向量更准的优势，又给 LLM 提供了父块完整上下文。</p>
 *
 * <p>聚合规则：</p>
 * <ul>
 *   <li>多个子块命中同一父块时，该父块只返回一次；</li>
 *   <li>父块之间按其命中的<b>最高子块相似度</b>排序；</li>
 *   <li>子块 metadata 必须携带 {@code parentId}（由 {@link ParentChildSplitter} 写入）。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class ParentChildRetriever implements Retriever {

	private final VectorStore childStore;
	private final EmbeddingProvider embeddingProvider;
	private final Map<String, Document> parentIndex;
	private final double minScore;

	private ParentChildRetriever(Builder builder) {
		this.childStore = builder.childStore;
		this.embeddingProvider = builder.embeddingProvider;
		this.parentIndex = builder.parentIndex == null
				? new HashMap<>() : new LinkedHashMap<>(builder.parentIndex);
		this.minScore = builder.minScore;
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

		// 1. 用 query 向量检索子块库（取较多候选以便聚合父块）
		float[] embedding = this.embeddingProvider.embed(query);
		int childCandidateK = Math.max(topK * 4, topK);
		List<SimilaritySearchResult> childHits =
				this.childStore.similaritySearch(embedding, childCandidateK, this.minScore);

		// 2. 按 parentId 聚合：记录每个父块命中的最高子块得分
		Map<String, Double> bestScoreByParent = new LinkedHashMap<>();
		for (SimilaritySearchResult hit : childHits) {
			String parentId = hit.metadata().get(ParentChildSplitter.META_PARENT_ID);
			if (parentId == null) {
				continue;
			}
			bestScoreByParent.merge(parentId, hit.score(), Math::max);
		}

		// 3. 按最高子块得分降序，取回父块全文
		List<Map.Entry<String, Double>> ranked = new ArrayList<>(bestScoreByParent.entrySet());
		ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		int limit = Math.min(topK, ranked.size());
		List<Document> result = new ArrayList<>(limit);
		for (int i = 0; i < limit; i++) {
			String parentId = ranked.get(i).getKey();
			Document parent = this.parentIndex.get(parentId);
			if (parent != null) {
				result.add(parent);
			}
		}
		return result;
	}

	/**
	 * ParentChildRetriever 构造器。
	 */
	public static final class Builder {

		private VectorStore childStore;
		private EmbeddingProvider embeddingProvider;
		private Map<String, Document> parentIndex;
		private double minScore = Double.NEGATIVE_INFINITY;

		private Builder() {
		}

		/**
		 * 设置存放子块向量的向量存储（必填）。
		 *
		 * @param childStore 子块向量库
		 * @return this
		 */
		public Builder childStore(VectorStore childStore) {
			this.childStore = childStore;
			return this;
		}

		/**
		 * 设置向量化实现（必填，对 query 向量化）。
		 *
		 * @param embeddingProvider 向量化实现
		 * @return this
		 */
		public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
			this.embeddingProvider = embeddingProvider;
			return this;
		}

		/**
		 * 设置父块索引（parentId → 父块文档），通常来自
		 * {@link ParentChildSplitter.ParentChunks#parentIndex()}。
		 *
		 * @param parentIndex 父块索引
		 * @return this
		 */
		public Builder parentIndex(Map<String, Document> parentIndex) {
			this.parentIndex = parentIndex;
			return this;
		}

		/**
		 * 设置最低相似度阈值（对子块检索生效）。
		 *
		 * @param minScore 阈值
		 * @return this
		 */
		public Builder minScore(double minScore) {
			this.minScore = minScore;
			return this;
		}

		/**
		 * 构建父子检索器。
		 *
		 * @return 检索器
		 */
		public ParentChildRetriever build() {
			Assert.notNull(this.childStore, "childStore 不能为 null");
			Assert.notNull(this.embeddingProvider, "embeddingProvider 不能为 null");
			return new ParentChildRetriever(this);
		}
	}
}
