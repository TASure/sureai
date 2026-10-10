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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.function.Consumer;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.rag.store.VectorStore;
import com.sure.ai.rag.splitter.TextSplitter;

/**
 * RagPipeline getter 补覆盖测试：用匿名空实现构造管线，调用 chatModel/embeddingProvider/
 * splitter/retriever getter。
 *
 * @author sureai
 * @since 2.6.0
 */
public class RagPipelineGettersExtraTest {

	/** 空实现 AiClient。 */
	private static final AiClient NOOP_CHAT_CLIENT = new AiClient() {
		@Override
		public String name() {
			return "noop";
		}

		@Override
		public com.sure.ai.model.ChatResponse chat(ChatRequest request) {
			return null;
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	};

	/** 空实现 EmbeddingClient。 */
	private static final EmbeddingClient NOOP_EMBED_CLIENT = new EmbeddingClient() {
		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			return null;
		}
	};

	/** 空实现 VectorStore。 */
	private static final VectorStore NOOP_STORE = new VectorStore() {
		@Override
		public void add(com.sure.ai.rag.model.Vector vector) {
		}

		@Override
		public void addAll(List<com.sure.ai.rag.model.Vector> vectors) {
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
		public List<com.sure.ai.rag.model.SimilaritySearchResult> similaritySearch(
				float[] queryEmbedding, int topK) {
			return List.of();
		}

		@Override
		public List<com.sure.ai.rag.model.SimilaritySearchResult> similaritySearch(
				float[] queryEmbedding, int topK, double minScore) {
			return List.of();
		}
	};

	/** 空实现 TextSplitter。 */
	private static final TextSplitter NOOP_SPLITTER = new TextSplitter() {
		@Override
		public List<String> split(String text) {
			return List.of();
		}
	};

	/** 构造 RagPipeline 并调用 getter。 */
	@Test
	public void testRagPipelineGetters() {
		RagPipeline pipeline = RagPipeline.builder()
				.chatClient(NOOP_CHAT_CLIENT)
				.chatModel("test-model")
				.embeddingClient(NOOP_EMBED_CLIENT)
				.embeddingModel("embed-model")
				.vectorStore(NOOP_STORE)
				.splitter(NOOP_SPLITTER)
				.defaultTopK(5)
				.build();
		assertEquals("test-model", pipeline.chatModel());
		assertNotNull(pipeline.embeddingProvider());
		assertNotNull(pipeline.splitter());
		assertNotNull(pipeline.retriever());
		assertNotNull(pipeline);
	}
}
