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

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.store.VectorStore;
import com.sure.tool.lang.Assert;

/**
 * 向量检索器：查询向量化 → 向量库相似度检索 → 文档化。
 *
 * @author sureai
 * @since 0.2.0
 */
public class VectorRetriever implements Retriever {

	private final VectorStore store;
	private final EmbeddingProvider embeddingProvider;
	private final double minScore;

	/**
	 * 创建向量检索器。
	 *
	 * @param store 向量存储
	 * @param embeddingProvider 向量化实现
	 */
	public VectorRetriever(VectorStore store, EmbeddingProvider embeddingProvider) {
		this(store, embeddingProvider, Double.NEGATIVE_INFINITY);
	}

	/**
	 * 创建带相似度阈值的向量检索器。
	 *
	 * @param store 向量存储
	 * @param embeddingProvider 向量化实现
	 * @param minScore 最低相似度阈值（含），低于该值的条目被过滤
	 */
	public VectorRetriever(VectorStore store, EmbeddingProvider embeddingProvider, double minScore) {
		this.store = Assert.notNull(store, "store 不能为 null");
		this.embeddingProvider = Assert.notNull(embeddingProvider, "embeddingProvider 不能为 null");
		this.minScore = minScore;
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
	 * 检索并保留相似度得分。
	 *
	 * @param query 用户查询
	 * @param topK 返回条数，必须大于 0
	 * @return 带得分的检索结果（按相似度降序）
	 */
	public List<SimilaritySearchResult> retrieveWithScores(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		float[] queryEmbedding = embeddingProvider.embed(query);
		return store.similaritySearch(queryEmbedding, topK, minScore);
	}
}
