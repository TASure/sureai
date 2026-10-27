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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.TestChatClient;
import com.sure.ai.rag.embedding.ClientEmbeddingProvider;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.InMemoryVectorStore;
import com.sure.ai.rag.store.VectorStore;
import com.sure.ai.rag.TestEmbeddingClient;

/**
 * HyDE 检索器测试。
 */
public class HydeRetrieverTest {

	/** 记录 embed 输入的向量化实现。 */
	private static final class RecordingEmbeddingProvider implements EmbeddingProvider {

		private final EmbeddingProvider delegate;
		private final List<String> embeddedTexts = new ArrayList<>();

		RecordingEmbeddingProvider(EmbeddingProvider delegate) {
			this.delegate = delegate;
		}

		@Override
		public float[] embed(String text) {
			this.embeddedTexts.add(text);
			return this.delegate.embed(text);
		}

		@Override
		public List<float[]> embedAll(List<String> texts) {
			return this.delegate.embedAll(texts);
		}
	}

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
	public void testLlmCalledAndEmbedUsesHypotheticalAnswer() {
		VectorStore store = buildStore("北京是中国的首都。", "苹果是一种水果。");
		RecordingEmbeddingProvider provider = new RecordingEmbeddingProvider(
				new ClientEmbeddingProvider(new TestEmbeddingClient(), "m"));
		// LLM 返回的「假设答案」是陈述式文本
		TestChatClient chatClient = new TestChatClient("北京是中国的首都，位于华北平原北部。");

		HydeRetriever retriever = HydeRetriever.builder()
				.chatClient(chatClient).model("test-model")
				.embeddingProvider(provider).store(store)
				.build();

		List<Document> docs = retriever.retrieve("北京在哪？", 1);

		// 1. LLM 被调用，且提示词含原 query
		assertNotNull(chatClient.lastRequest());
		assertTrue(chatClient.lastRequest().messages().get(0).content().contains("北京在哪？"));

		// 2. 向量化输入是「假设答案」而非原始 query
		assertEquals(1, provider.embeddedTexts.size());
		assertEquals("北京是中国的首都，位于华北平原北部。", provider.embeddedTexts.get(0));

		// 3. 返回的是假设答案最接近的真实文档
		assertEquals(1, docs.size());
		assertEquals("北京是中国的首都。", docs.get(0).text());
	}

	@Test
	public void testLlmFailureFallsBackToOriginalQuery() {
		VectorStore store = buildStore("北京是中国的首都。");
		RecordingEmbeddingProvider provider = new RecordingEmbeddingProvider(
				new ClientEmbeddingProvider(new TestEmbeddingClient(), "m"));
		// 一个会抛异常的 chatClient
		AiClient throwingClient = new AiClient() {
			@Override
			public String name() {
				return "throwing";
			}

			@Override
			public ChatResponse chat(ChatRequest request) {
				throw new RuntimeException("llm down");
			}

			@Override
			public void chatStream(ChatRequest request,
					java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> consumer) {
				// no-op
			}

			@Override
			public void close() {
				// no-op
			}
		};

		HydeRetriever retriever = HydeRetriever.builder()
				.chatClient(throwingClient).model("test-model")
				.embeddingProvider(provider).store(store)
				.build();

		List<Document> docs = retriever.retrieve("北京是中国的首都。", 1);

		// 回退：向量化输入应为原始 query，且不抛异常
		assertEquals(1, provider.embeddedTexts.size());
		assertEquals("北京是中国的首都。", provider.embeddedTexts.get(0));
		assertEquals(1, docs.size());
		assertEquals("id-0", docs.get(0).id());
	}

	@Test
	public void testBlankHypotheticalFallsBackToQuery() {
		VectorStore store = buildStore("北京是中国的首都。");
		RecordingEmbeddingProvider provider = new RecordingEmbeddingProvider(
				new ClientEmbeddingProvider(new TestEmbeddingClient(), "m"));
		// LLM 返回空串
		TestChatClient chatClient = new TestChatClient("   ");

		HydeRetriever retriever = HydeRetriever.builder()
				.chatClient(chatClient).model("test-model")
				.embeddingProvider(provider).store(store)
				.build();

		List<Document> docs = retriever.retrieve("北京是中国的首都。", 5);
		assertEquals("北京是中国的首都。", provider.embeddedTexts.get(0));
		assertNotNull(docs);
	}
}
