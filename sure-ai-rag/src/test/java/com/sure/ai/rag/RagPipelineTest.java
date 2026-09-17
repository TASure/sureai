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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.rag.store.InMemoryVectorStore;

/**
 * RAG 管线端到端测试（本地假实现，不访问网络）。
 */
public class RagPipelineTest {

	private static final String CHAT_MODEL = "test-chat-model";
	private static final String EMBED_MODEL = "test-embed-model";

	/**
	 * 创建带小块分块器的管线（确保测试文本能切出多块）。
	 */
	private RagPipeline buildPipeline() {
		return RagPipeline.builder()
				.chatClient(new TestChatClient("测试回答"))
				.chatModel(CHAT_MODEL)
				.embeddingClient(new TestEmbeddingClient())
				.embeddingModel(EMBED_MODEL)
				.splitter(new com.sure.ai.rag.splitter.RecursiveCharacterTextSplitter(12, 5,
						null, true))
				.build();
	}

	@Test
	public void testIngestAndAsk() {
		RagPipeline pipeline = buildPipeline();
		int chunks = pipeline.ingest("doc-1", "北京是中国的首都。\n\n上海是中国的经济中心。");
		assertTrue(chunks >= 2);
		assertTrue(pipeline.vectorStore().size() >= 2);

		ChatResponse response = pipeline.ask("北京是中国的首都吗？");
		assertEquals("测试回答", response.firstText());
		assertEquals(CHAT_MODEL, response.model());

		TestChatClient chatClient = (TestChatClient) pipeline.chatClient();
		ChatRequest request = chatClient.lastRequest();
		List<ChatMessage> messages = request.messages();
		assertEquals(2, messages.size());
		assertEquals("system", messages.get(0).role().value());
		assertTrue(messages.get(0).content().contains("北京是中国的首都"));
		assertEquals("user", messages.get(1).role().value());
		assertEquals("北京是中国的首都吗？", messages.get(1).content());
	}

	@Test
	public void testAskWithCustomTopK() {
		RagPipeline pipeline = buildPipeline();
		pipeline.ingest("doc-1", "苹果是一种广受欢迎的水果。\n\n苹果树需要充足的阳光。");
		ChatResponse response = pipeline.ask("苹果有什么特点？", 1);
		assertEquals("测试回答", response.firstText());
		TestChatClient chatClient = (TestChatClient) pipeline.chatClient();
		String system = chatClient.lastRequest().messages().get(0).content();
		// topK=1 时只包含一段上下文
		assertTrue(system.contains("[1]"));
		assertFalse(system.contains("[2]"));
	}

	@Test
	public void testIngestDocumentWithMetadata() {
		RagPipeline pipeline = buildPipeline();
		int chunks = pipeline.ingest(Document.of("doc-2", "量子计算是前沿技术方向。",
				Map.of("source", "wiki")));
		assertEquals(1, chunks);
		List<SimilaritySearchResult> results =
				pipeline.retrieveWithScores("量子计算", 1);
		assertEquals(1, results.size());
		assertEquals("wiki", results.get(0).metadata().get("source"));
	}

	@Test
	public void testRetrieveEmpty() {
		RagPipeline pipeline = buildPipeline();
		List<Document> documents = pipeline.retrieve("空库查询", 3);
		assertTrue(documents.isEmpty());
		// 空上下文也能问答
		ChatResponse response = pipeline.ask("空库问题");
		assertEquals("测试回答", response.firstText());
		TestChatClient chatClient = (TestChatClient) pipeline.chatClient();
		assertTrue(chatClient.lastRequest().messages().get(0).content().contains("上下文：\n"));
	}

	@Test
	public void testCustomSystemPromptTemplate() {
		RagPipeline pipeline = RagPipeline.builder()
				.chatClient(new TestChatClient("x"))
				.chatModel(CHAT_MODEL)
				.embeddingClient(new TestEmbeddingClient())
				.embeddingModel(EMBED_MODEL)
				.systemPromptTemplate("请参考：{context}")
				.build();
		pipeline.ingest("d", "自定义模板内容。");
		pipeline.ask("测试");
		TestChatClient chatClient = (TestChatClient) pipeline.chatClient();
		assertTrue(chatClient.lastRequest().messages().get(0).content()
				.startsWith("请参考："));
		assertTrue(chatClient.lastRequest().messages().get(0).content()
				.contains("自定义模板内容"));
	}

	@Test
	public void testCustomRetrieverAndStore() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		RagPipeline pipeline = RagPipeline.builder()
				.chatClient(new TestChatClient("x"))
				.chatModel(CHAT_MODEL)
				.embeddingClient(new TestEmbeddingClient())
				.embeddingModel(EMBED_MODEL)
				.vectorStore(store)
				.defaultTopK(2)
				.build();
		pipeline.ingest("d1", "内容甲。");
		pipeline.ingest("d2", "内容乙。");
		assertEquals(2, store.size());
		assertEquals(2, pipeline.retrieve("内容甲", 2).size());
	}

	@Test
	public void testMinScoreBuilder() {
		RagPipeline pipeline = RagPipeline.builder()
				.chatClient(new TestChatClient("x"))
				.chatModel(CHAT_MODEL)
				.embeddingClient(new TestEmbeddingClient())
				.embeddingModel(EMBED_MODEL)
				.minScore(0.99)
				.build();
		pipeline.ingest("d", "完全一致的文本。");
		pipeline.ingest("d2", "完全不同的其他文本内容。");
		List<Document> docs = pipeline.retrieve("完全一致的文本。", 5);
		assertEquals(1, docs.size());
		assertEquals("d#0", docs.get(0).id());
	}

	@Test
	public void testIngestDocumentsDirect() {
		RagPipeline pipeline = buildPipeline();
		int count = pipeline.ingestDocuments(List.of(
				Document.of("c1", "第一段内容。"),
				Document.of("c2", "第二段内容。")));
		assertEquals(2, count);
		assertEquals(0, pipeline.ingestDocuments(null));
		assertEquals(0, pipeline.ingestDocuments(List.of()));
	}

	@Test
	public void testDescribe() {
		RagPipeline pipeline = buildPipeline();
		Map<String, Object> info = pipeline.describe();
		assertEquals(CHAT_MODEL, info.get("chatModel"));
		assertEquals("InMemoryVectorStore", info.get("vectorStore"));
		assertEquals(RagPipeline.DEFAULT_TOP_K, info.get("defaultTopK"));
	}
}
