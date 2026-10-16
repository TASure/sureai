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

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.VectorStore;

/**
 * {@link MultimodalIngestor} 补覆盖测试：用匿名空实现 VectorStore/EmbeddingProvider
 * 构造实例，调用 ingestAll(空列表) 与 size()，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class MultimodalIngestorExtraTest {

	/** 空实现 EmbeddingProvider。 */
	private static final EmbeddingProvider NOOP_EMBED = new EmbeddingProvider() {
		@Override
		public float[] embed(String text) {
			return new float[] { 0.0f };
		}

		@Override
		public List<float[]> embedAll(List<String> texts) {
			return texts.stream().map(t -> new float[] { 0.0f }).toList();
		}
	};

	/** 空实现 VectorStore。 */
	private static final VectorStore NOOP_STORE = new VectorStore() {
		@Override
		public void add(Vector vector) {
		}

		@Override
		public void addAll(List<Vector> vectors) {
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
			return 0;
		}

		@Override
		public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK) {
			return List.of();
		}

		@Override
		public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
				double minScore) {
			return List.of();
		}
	};

	/** ingestAll(空列表) + size()。 */
	@Test
	public void testIngestAllEmptyAndSize() {
		MultimodalIngestor ingestor = MultimodalIngestor.builder()
				.store(NOOP_STORE).textEmbeddingProvider(NOOP_EMBED).build();
		ingestor.ingestAll(List.of());
		assertEquals(0, ingestor.size());
		assertNotNull(ingestor);
	}
}
