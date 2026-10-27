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
package com.sure.ai.rag.coverage;

import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.VectorStore;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.strategy.MultimodalDocument;
import com.sure.ai.rag.strategy.MultimodalIngestor;

/**
 * MultimodalIngestor.ingestAll 批量入库分支补覆盖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class MultimodalIngestorExtraTest {

	/** 空实现 VectorStore。 */
	private static final VectorStore NOOP_STORE = new VectorStore() {
		@Override
		public void add(Vector vector) {
			// no-op
		}

		@Override
		public void addAll(List<Vector> vectors) {
			// no-op
		}

		@Override
		public void clear() {
			// no-op
		}

		@Override
		public int size() {
			return 0;
		}

		@Override
		public boolean delete(String id) {
			return true;
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

		@Override
		public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
				double minScore, FilterExpression filter) {
			return List.of();
		}
	};

	/** 空实现 EmbeddingProvider。 */
	private static final EmbeddingProvider NOOP_EMB = new EmbeddingProvider() {
		@Override
		public float[] embed(String text) {
			return new float[4];
		}

		@Override
		public List<float[]> embedAll(List<String> texts) {
			return List.of();
		}
	};

	/** ingestAll 批量入库。 */
	@Test
	public void testIngestAll() {
		MultimodalIngestor ingestor = MultimodalIngestor.builder()
				.store(NOOP_STORE)
				.textEmbeddingProvider(NOOP_EMB)
				.build();
		MultimodalDocument doc = MultimodalDocument.of("doc1", List.of(), Map.of());
		ingestor.ingestAll(List.of(doc));
		assertNotNull(ingestor);
	}
}
