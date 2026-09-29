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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.store.VectorStore;
import com.sure.tool.lang.Assert;

/**
 * 多模态检索器：查询为文本时按文本向量检索，命中后返回保留完整 parts（含图片引用）的
 * {@link MultimodalDocument}。
 *
 * <p>既实现了通用 {@link Retriever}（返回文本聚合后的 {@link Document}，可无缝接入既有
 * RAG 管线），也提供 {@link #retrieveMultimodal(String, int)} 返回多模态文档，供上层把
 * 图片片段一并喂给多模态模型。</p>
 *
 * <p>去重规则：文本向量命中与图片向量命中可能指向同一篇多模态文档，按
 * {@code parentDocId}（图片向量）或文档 id（文本向量）聚合，同一文档只返回一次，
 * 按命中相似度最高分排序。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class MultimodalRetriever implements Retriever {

	private final VectorStore store;
	private final EmbeddingProvider textEmbeddingProvider;
	private final MultimodalIngestor ingestor;
	private final double minScore;

	private MultimodalRetriever(Builder builder) {
		this.store = builder.store;
		this.textEmbeddingProvider = builder.textEmbeddingProvider;
		this.ingestor = builder.ingestor;
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
		List<MultimodalDocument> multimodalDocs = retrieveMultimodal(query, topK);
		List<Document> result = new ArrayList<>(multimodalDocs.size());
		for (MultimodalDocument doc : multimodalDocs) {
			result.add(Document.of(doc.id(), doc.text(), doc.metadata()));
		}
		return result;
	}

	/**
	 * 检索并返回保留完整多模态片段的文档。
	 *
	 * @param query 用户查询（文本）
	 * @param topK 返回条数
	 * @return 多模态文档列表（按相关度降序，已去重）
	 */
	public List<MultimodalDocument> retrieveMultimodal(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);

		float[] embedding = this.textEmbeddingProvider.embed(query);
		List<SimilaritySearchResult> hits = this.store.similaritySearch(embedding, topK, this.minScore);

		// 按文档 id 聚合：同一文档可能被文本向量与多张图片向量同时命中
		Map<String, Double> bestScoreByDoc = new LinkedHashMap<>();
		for (SimilaritySearchResult hit : hits) {
			String kind = hit.metadata().get(MultimodalIngestor.META_KIND);
			String docId;
			if ("image".equals(kind)) {
				docId = hit.metadata().get(MultimodalIngestor.META_PARENT_DOC_ID);
			} else {
				docId = hit.id();
			}
			if (docId == null) {
				continue;
			}
			bestScoreByDoc.merge(docId, hit.score(), Math::max);
		}

		List<Map.Entry<String, Double>> ranked = new ArrayList<>(bestScoreByDoc.entrySet());
		ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		int limit = Math.min(topK, ranked.size());
		List<MultimodalDocument> result = new ArrayList<>(limit);
		for (int i = 0; i < limit; i++) {
			MultimodalDocument doc = this.ingestor.get(ranked.get(i).getKey());
			if (doc != null) {
				result.add(doc);
			}
		}
		return result;
	}

	/**
	 * MultimodalRetriever 构造器。
	 */
	public static final class Builder {

		private VectorStore store;
		private EmbeddingProvider textEmbeddingProvider;
		private MultimodalIngestor ingestor;
		private double minScore = Double.NEGATIVE_INFINITY;

		private Builder() {
		}

		/**
		 * 设置向量存储（必填）。
		 *
		 * @param store 向量存储
		 * @return this
		 */
		public Builder store(VectorStore store) {
			this.store = store;
			return this;
		}

		/**
		 * 设置文本向量化实现（必填，对 query 向量化）。
		 *
		 * @param textEmbeddingProvider 文本向量化实现
		 * @return this
		 */
		public Builder textEmbeddingProvider(EmbeddingProvider textEmbeddingProvider) {
			this.textEmbeddingProvider = textEmbeddingProvider;
			return this;
		}

		/**
		 * 设置入库器（用于命中后还原多模态文档，必填）。
		 *
		 * @param ingestor 入库器
		 * @return this
		 */
		public Builder ingestor(MultimodalIngestor ingestor) {
			this.ingestor = ingestor;
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
		 * 构建多模态检索器。
		 *
		 * @return 检索器
		 */
		public MultimodalRetriever build() {
			Assert.notNull(this.store, "store 不能为 null");
			Assert.notNull(this.textEmbeddingProvider, "textEmbeddingProvider 不能为 null");
			Assert.notNull(this.ingestor, "ingestor 不能为 null");
			return new MultimodalRetriever(this);
		}
	}
}
