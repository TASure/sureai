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
package com.sure.ai.rag.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.model.Document;

/**
 * GraphRAG 端到端管线与检索器测试。
 */
public class GraphRagRetrieverTest {

	/** 按调用顺序返回脚本回复的对话客户端。 */
	private static final class QueueChatClient implements AiClient {

		private final Deque<String> replies;

		QueueChatClient(String... replies) {
			this.replies = new ArrayDeque<>(List.of(replies));
		}

		@Override
		public String name() {
			return "queue";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			String reply = this.replies.poll();
			if (reply == null) {
				throw new IllegalStateException("脚本回复已耗尽");
			}
			return ChatResponse.of("r1", request.model(),
					List.of(com.sure.ai.model.Choice.of(0, ChatMessage.assistant(reply), "stop")),
					com.sure.ai.model.TokenUsage.of(1, 1, 2), null);
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
	}

	private GraphRagIndexer buildIndexer(String doc1Triples, String doc2Triples, String... summaries) {
		// 抽取器对两篇文档返回固定三元组；摘要器逐社区返回脚本摘要
		AiClient extractClient = new QueueChatClient(doc1Triples, doc2Triples);
		AiClient summaryClient = new QueueChatClient(summaries);

		EntityRelationExtractor extractor = LlmEntityRelationExtractor.builder()
				.chatClient(extractClient).model("m").build();
		CommunitySummarizer summarizer = LlmCommunitySummarizer.builder()
				.chatClient(summaryClient).model("m").build();

		return GraphRagIndexer.builder()
				.extractor(extractor)
				.summarizer(summarizer)
				.build();
	}

	@Test
	public void testEndToEndIngestBuildsCommunitiesAndSummaries() {
		GraphRagIndexer indexer = buildIndexer(
				"Alice | worksAt | Meta\nBob | worksAt | Meta",
				"Beijing | locatedIn | China\nShanghai | locatedIn | China",
				"科技行业社区", "地理城市社区");

		indexer.ingest(List.of(
				Document.of("d1", "员工文本"),
				Document.of("d2", "城市文本")));

		List<GraphCommunity> communities = indexer.communities();
		assertEquals(2, communities.size());
		assertTrue(communities.stream().allMatch(c -> c.summary() != null && !c.summary().isBlank()));
	}

	@Test
	public void testRetrieverHitsCommunitySummary() {
		GraphRagIndexer indexer = buildIndexer(
				"Alice | worksAt | Meta\nBob | worksAt | Meta",
				"Beijing | locatedIn | China",
				"科技社区摘要", "地理社区摘要");
		indexer.ingest(List.of(
				Document.of("d1", "员工"),
				Document.of("d2", "城市")));

		GraphCommunity aliceCommunity = indexer.communities().stream()
				.filter(c -> c.entityIds().contains("alice"))
				.findFirst().orElseThrow();
		GraphRagRetriever retriever = GraphRagRetriever.builder().indexer(indexer).build();
		List<Document> results = retriever.retrieve("Alice 在哪工作？", 3);

		assertEquals(1, results.size());
		assertEquals(aliceCommunity.summary(), results.get(0).text());
	}

	@Test
	public void testRetrieverNoHitReturnsEmpty() {
		GraphRagIndexer indexer = buildIndexer(
				"Alice | worksAt | Meta",
				"Beijing | locatedIn | China",
				"科技社区", "地理社区");
		indexer.ingest(List.of(
				Document.of("d1", "员工"),
				Document.of("d2", "城市")));

		GraphRagRetriever retriever = GraphRagRetriever.builder().indexer(indexer).build();
		List<Document> results = retriever.retrieve("火星上有没有外星人", 3);

		assertTrue(results.isEmpty());
	}

	@Test
	public void testMultiCommunityRankingByHitCount() {
		GraphRagIndexer indexer = buildIndexer(
				"Alice | worksAt | Meta\nBob | worksAt | Meta",
				"Beijing | locatedIn | China",
				"社区零", "社区一");
		indexer.ingest(List.of(
				Document.of("d1", "员工"),
				Document.of("d2", "城市")));

		GraphRagRetriever retriever = GraphRagRetriever.builder().indexer(indexer).build();
		// query 命中 alice、bob 两个实体的社区，与命中 beijing 一个实体的社区
		List<Document> results = retriever.retrieve("Alice Bob Beijing 之间", 5);

		int techId = indexer.communities().stream()
				.filter(c -> c.entityIds().contains("alice"))
				.findFirst().orElseThrow().communityId();
		int geoId = indexer.communities().stream()
				.filter(c -> c.entityIds().contains("beijing"))
				.findFirst().orElseThrow().communityId();

		assertEquals(2, results.size());
		List<String> ids = new ArrayList<>();
		for (Document doc : results) {
			ids.add(doc.id());
		}
		// 命中数多（2）的科技社区排第一
		assertEquals("graph-community-" + techId, ids.get(0));
		assertEquals("graph-community-" + geoId, ids.get(1));
	}
}
