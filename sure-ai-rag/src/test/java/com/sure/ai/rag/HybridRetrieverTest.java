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
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.retriever.HybridRetriever;
import com.sure.ai.rag.retriever.KeywordRetriever;
import com.sure.ai.rag.retriever.VectorRetriever;
import com.sure.ai.rag.store.InMemoryVectorStore;
import com.sure.ai.rag.store.VectorStore;

/**
 * {@link HybridRetriever} 测试：确定性向量 + BM25 融合，零真实网络。
 */
public class HybridRetrieverTest {

	/** 构建向量库。 */
	private VectorStore buildStore(String[] ids, String[] texts) {
		InMemoryVectorStore store = new InMemoryVectorStore();
		for (int i = 0; i < ids.length; i++) {
			store.add(Vector.of(ids[i], TestEmbeddingClient.hashEmbedding(texts[i]), texts[i]));
		}
		return store;
	}

	/** 构建向量检索器。 */
	private VectorRetriever vectorRetriever(VectorStore store) {
		EmbeddingProvider provider = new ClientEmbeddingProvider(new TestEmbeddingClient(),
				"test-embedding");
		return new VectorRetriever(store, provider);
	}

	@Test
	public void testHybridFusion() {
		String[] ids = { "both", "vonly", "konly" };
		String[] texts = { "quick fox", "quick foxes jumping rapidly", "lazy quick dog" };
		VectorStore store = buildStore(ids, texts);
		VectorRetriever vr = vectorRetriever(store);
		KeywordRetriever kr = new KeywordRetriever(List.of(
				Document.of("both", texts[0]),
				Document.of("vonly", texts[1]),
				Document.of("konly", texts[2])));
		HybridRetriever hybrid = new HybridRetriever(vr, kr);
		List<Document> results = hybrid.retrieve("quick fox", 2);
		assertTrue(results.size() <= 2);
		// 同时命中两路且向量完全相同的文档应排第一
		assertEquals("both", results.get(0).id());
	}

	@Test
	public void testWeights() {
		// V：向量完全相同（精确等于查询），关键词弱；K：关键词强（词频高）
		String vText = "alpha";
		StringBuilder kText = new StringBuilder();
		for (int i = 0; i < 8; i++) {
			if (i > 0) {
				kText.append(' ');
			}
			kText.append("alpha");
		}
		VectorStore store = buildStore(new String[] { "v", "k" }, new String[] { vText, kText.toString() });
		VectorRetriever vr = vectorRetriever(store);
		KeywordRetriever kr = new KeywordRetriever(List.of(
				Document.of("v", vText),
				Document.of("k", kText.toString())));

		// 向量权重高：v 排第一
		HybridRetriever vectorHeavy = new HybridRetriever(vr, kr, 0.8, 0.2);
		List<Document> vecResults = vectorHeavy.retrieve("alpha", 2);
		assertEquals("v", vecResults.get(0).id());

		// 关键词权重高：k 排第一
		HybridRetriever keywordHeavy = new HybridRetriever(vr, kr, 0.2, 0.8);
		List<Document> kwResults = keywordHeavy.retrieve("alpha", 2);
		assertEquals("k", kwResults.get(0).id());
	}

	@Test
	public void testTopK() {
		String[] ids = { "a", "b", "c", "d" };
		String[] texts = { "quick fox", "quick fox run", "lazy dog", "brown cat" };
		VectorStore store = buildStore(ids, texts);
		VectorRetriever vr = vectorRetriever(store);
		KeywordRetriever kr = new KeywordRetriever(List.of(
				Document.of("a", texts[0]),
				Document.of("b", texts[1]),
				Document.of("c", texts[2]),
				Document.of("d", texts[3])));
		HybridRetriever hybrid = new HybridRetriever(vr, kr);
		List<Document> results = hybrid.retrieve("quick fox", 2);
		assertEquals(2, results.size());
	}

	@Test
	public void testVectorOnlyMatch() {
		// 向量精确命中的文档即使关键词路贡献弱也应出现在结果中
		String[] ids = { "exact", "other" };
		String[] texts = { "quantum zebra", "completely unrelated content here" };
		VectorStore store = buildStore(ids, texts);
		VectorRetriever vr = vectorRetriever(store);
		KeywordRetriever kr = new KeywordRetriever(List.of(
				Document.of("exact", texts[0]),
				Document.of("other", texts[1])));
		HybridRetriever hybrid = new HybridRetriever(vr, kr);
		List<Document> results = hybrid.retrieve("quantum zebra", 2);
		assertEquals("exact", results.get(0).id());
		assertEquals(2, results.size());
	}
}
