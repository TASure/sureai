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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.TextPart;
import com.sure.ai.rag.TestEmbeddingClient;
import com.sure.ai.rag.embedding.ClientEmbeddingProvider;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.store.InMemoryVectorStore;
import com.sure.ai.rag.store.VectorStore;

/**
 * 多模态 RAG 入库与检索测试。
 */
public class MultimodalRetrieverTest {

	private static final String TEXT = "苹果是一种常见水果。";

	private MultimodalDocument sampleDoc() {
		List<MessagePart> parts = List.of(
				TextPart.of(TEXT),
				ImagePart.ofUrl("http://example.com/apple.png"));
		return MultimodalDocument.of("doc1", parts);
	}

	@Test
	public void testWithImageEmbedderStoresBothAndRetrievesParts() {
		VectorStore store = new InMemoryVectorStore();
		ClientEmbeddingProvider textProvider = new ClientEmbeddingProvider(
				new TestEmbeddingClient(), "m");
		ImageEmbedder imageEmbedder = part -> new float[] { 1f, 0f, 0f };

		MultimodalIngestor ingestor = MultimodalIngestor.builder()
				.store(store).textEmbeddingProvider(textProvider)
				.imageEmbedder(imageEmbedder)
				.build();
		ingestor.ingest(sampleDoc());

		// 文本向量 + 图片向量各一条
		assertEquals(2, store.size());

		MultimodalRetriever retriever = MultimodalRetriever.builder()
				.store(store).textEmbeddingProvider(textProvider).ingestor(ingestor)
				.build();
		List<MultimodalDocument> results = retriever.retrieveMultimodal(TEXT, 5);

		assertEquals(1, results.size());
		MultimodalDocument doc = results.get(0);
		assertEquals(2, doc.parts().size());
		assertTrue(doc.parts().get(0) instanceof TextPart);
		assertTrue(doc.parts().get(1) instanceof ImagePart);
		assertEquals(1, doc.imageCount());
	}

	@Test
	public void testWithoutImageEmbedderRegistersMetadataAndTextSearchWorks() {
		VectorStore store = new InMemoryVectorStore();
		ClientEmbeddingProvider textProvider = new ClientEmbeddingProvider(
				new TestEmbeddingClient(), "m");

		MultimodalIngestor ingestor = MultimodalIngestor.builder()
				.store(store).textEmbeddingProvider(textProvider)
				.build();
		ingestor.ingest(sampleDoc());

		// 未注入 ImageEmbedder：仅文本向量入库
		assertEquals(1, store.size());

		// 文本向量 metadata 登记了图片数量
		List<SimilaritySearchResult> hits = store.similaritySearch(
				textProvider.embed(TEXT), 5);
		assertEquals("1", hits.get(0).metadata().get(MultimodalIngestor.META_IMAGE_COUNT));
		assertEquals("text", hits.get(0).metadata().get(MultimodalIngestor.META_KIND));

		// 文本检索仍工作，且返回的 parts 同时含文本与图片
		MultimodalRetriever retriever = MultimodalRetriever.builder()
				.store(store).textEmbeddingProvider(textProvider).ingestor(ingestor)
				.build();
		List<MultimodalDocument> results = retriever.retrieveMultimodal(TEXT, 5);
		assertEquals(1, results.size());
		assertEquals(2, results.get(0).parts().size());
		assertNotNull(results.get(0).images());
	}

	@Test
	public void testRetrieverAdapterReturnsTextDocuments() {
		VectorStore store = new InMemoryVectorStore();
		ClientEmbeddingProvider textProvider = new ClientEmbeddingProvider(
				new TestEmbeddingClient(), "m");
		MultimodalIngestor ingestor = MultimodalIngestor.builder()
				.store(store).textEmbeddingProvider(textProvider)
				.build();
		ingestor.ingest(sampleDoc());

		MultimodalRetriever retriever = MultimodalRetriever.builder()
				.store(store).textEmbeddingProvider(textProvider).ingestor(ingestor)
				.build();
		// 通用 Retriever 适配：返回文本聚合后的 Document
		assertEquals(1, retriever.retrieve(TEXT, 5).size());
		assertEquals(TEXT, retriever.retrieve(TEXT, 5).get(0).text());
	}
}
