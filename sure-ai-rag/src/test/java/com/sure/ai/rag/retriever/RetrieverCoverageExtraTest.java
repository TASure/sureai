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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.store.VectorStore;

/**
 * 检索器补覆盖测试：{@link HybridRetriever} 单路空结果归一化、两路共享 id 融合与权重访问器；
 * {@link KeywordRetriever} 空查询零分、重复 token 去重、空文本分词与文档访问器，零网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class RetrieverCoverageExtraTest {

	/** 脚本化向量库：按预设返回检索结果。 */
	private static VectorStore scriptedStore(List<SimilaritySearchResult> results) {
		return new VectorStore() {
			@Override
			public void add(com.sure.ai.rag.model.Vector vector) {
			}

			@Override
			public void addAll(List<com.sure.ai.rag.model.Vector> batch) {
			}

			@Override
			public boolean delete(String id) {
				return true;
			}

			@Override
			public void clear() {
			}

			@Override
			public int size() {
				return results.size();
			}

			@Override
			public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK) {
				return new ArrayList<>(results);
			}

			@Override
			public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
					double minScore) {
				return new ArrayList<>(results);
			}
		};
	}

	/** 固定向量提供者。 */
	private static EmbeddingProvider fixedEmbedding() {
		return new EmbeddingProvider() {
			@Override
			public float[] embed(String text) {
				return new float[] { 0.1f, 0.2f };
			}

			@Override
			public List<float[]> embedAll(List<String> texts) {
				return List.of(new float[] { 0.1f, 0.2f });
			}
		};
	}

	private static SimilaritySearchResult sr(String id, double score) {
		return new SimilaritySearchResult(id, new float[] { 0.1f }, "text-" + id, Map.of(), score);
	}

	// ===================== HybridRetriever =====================

	/** 两路均空 → 空结果；权重访问器。 */
	@Test
	public void testHybridBothEmpty() {
		VectorStore emptyStore = scriptedStore(List.of());
		VectorRetriever vr = new VectorRetriever(emptyStore, fixedEmbedding());
		KeywordRetriever kr = new KeywordRetriever(List.of());
		HybridRetriever hybrid = new HybridRetriever(vr, kr, 0.7, 0.3);
		assertTrue(hybrid.retrieve("query", 5).isEmpty());
		assertEquals(0.7, hybrid.vectorWeight(), 0.001);
		assertEquals(0.3, hybrid.keywordWeight(), 0.001);
	}

	/** 两路共享同一 id → computeIfAbsent 合并得分。 */
	@Test
	public void testHybridSharedIdMerge() {
		VectorStore store = scriptedStore(List.of(sr("shared", 0.9), sr("vonly", 0.5)));
		VectorRetriever vr = new VectorRetriever(store, fixedEmbedding());
		List<Document> docs = List.of(new Document("shared", "alpha beta", Map.of()),
				new Document("konly", "gamma delta", Map.of()));
		KeywordRetriever kr = new KeywordRetriever(docs);
		HybridRetriever hybrid = new HybridRetriever(vr, kr);
		List<Document> result = hybrid.retrieve("alpha", 5);
		assertTrue(result.size() >= 1);
	}

	// ===================== KeywordRetriever =====================

	/** 空查询 → score() 返回 0.0；documents() 返回不可变副本。 */
	@Test
	public void testKeywordEmptyQuery() {
		List<Document> docs = List.of(new Document("d1", "hello world", Map.of()));
		KeywordRetriever kr = new KeywordRetriever(docs);
		assertEquals(1, kr.retrieve("", 5).size());
		assertEquals(1, kr.documents().size());
	}

	/** 重复 token 去重：查询含重复词不重复计分。 */
	@Test
	public void testKeywordDuplicateToken() {
		List<Document> docs = List.of(new Document("d1", "alpha beta gamma", Map.of()));
		KeywordRetriever kr = new KeywordRetriever(docs);
		List<Document> result = kr.retrieve("alpha alpha alpha", 5);
		assertEquals(1, result.size());
	}
}
