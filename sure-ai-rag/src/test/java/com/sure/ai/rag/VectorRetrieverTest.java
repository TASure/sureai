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

package com.sure.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.embedding.ClientEmbeddingProvider;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.retriever.VectorRetriever;
import com.sure.ai.rag.store.InMemoryVectorStore;
import com.sure.ai.rag.store.VectorStore;

/**
 * 向量检索器测试。
 */
public class VectorRetrieverTest {

	private VectorStore buildStore(String... texts) {
		InMemoryVectorStore store = new InMemoryVectorStore();
		int i = 0;
		for (String text : texts) {
			store.add(Vector.of("id-" + i, TestEmbeddingClient.hashEmbedding(text), text));
			i++;
		}
		return store;
	}

	@Test
	public void testRetrieveExactMatch() {
		VectorStore store = buildStore("北京是中国的首都。", "上海是中国的经济中心。");
		EmbeddingProvider provider = new ClientEmbeddingProvider(new TestEmbeddingClient(),
				"test-embedding");
		VectorRetriever retriever = new VectorRetriever(store, provider);
		List<Document> documents = retriever.retrieve("北京是中国的首都。", 1);
		assertEquals(1, documents.size());
		assertEquals("北京是中国的首都。", documents.get(0).text());
	}

	@Test
	public void testRetrieveWithScoresOrdered() {
		VectorStore store = buildStore("苹果是一种水果。", "汽车是一种交通工具。",
				"苹果树生长在果园里。");
		EmbeddingProvider provider = new ClientEmbeddingProvider(new TestEmbeddingClient(),
				"test-embedding");
		VectorRetriever retriever = new VectorRetriever(store, provider);
		List<SimilaritySearchResult> results = retriever.retrieveWithScores("苹果", 3);
		assertEquals(3, results.size());
		assertTrue(results.get(0).score() >= results.get(1).score());
		assertEquals("id-0", results.get(0).id());
	}

	@Test
	public void testRetrieveWithMinScore() {
		VectorStore store = buildStore("苹果是一种水果。", "苹果树生长在果园里。");
		EmbeddingProvider provider = new ClientEmbeddingProvider(new TestEmbeddingClient(),
				"test-embedding");
		VectorRetriever retriever = new VectorRetriever(store, provider, 0.99);
		// 完全相同的文本余弦相似度为 1.0，其余低于 0.99
		List<Document> documents = retriever.retrieve("苹果是一种水果。", 5);
		assertEquals(1, documents.size());
		assertEquals("id-0", documents.get(0).id());
	}
}
