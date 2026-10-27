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

import java.util.List;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.TestChatClient;

/**
 * LLM 社区摘要器测试。
 */
public class LlmCommunitySummarizerTest {

	private KnowledgeGraph sampleGraph() {
		KnowledgeGraph graph = new KnowledgeGraph();
		graph.addRelation("Alice", "Meta", "worksAt", null);
		return graph;
	}

	private LlmCommunitySummarizer summarizerWith(AiClient client) {
		return LlmCommunitySummarizer.builder()
				.chatClient(client).model("test-model").build();
	}

	@Test
	public void testScriptedSummaryReturned() {
		KnowledgeGraph graph = sampleGraph();
		GraphCommunity community = new GraphCommunity(0, List.of("alice", "meta"), null);
		LlmCommunitySummarizer summarizer = summarizerWith(
				new TestChatClient("这是关于 Alice 任职 Meta 的社区摘要。"));

		String summary = summarizer.summarize(community, graph, List.of());

		assertEquals("这是关于 Alice 任职 Meta 的社区摘要。", summary);
	}

	@Test
	public void testFallbackToMechanicalJoinOnException() {
		KnowledgeGraph graph = sampleGraph();
		GraphCommunity community = new GraphCommunity(0, List.of("alice", "meta"), null);
		AiClient throwing = new AiClient() {
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
		LlmCommunitySummarizer summarizer = summarizerWith(throwing);

		String summary = summarizer.summarize(community, graph, List.of());

		assertTrue(summary.contains("Alice"));
		assertTrue(summary.contains("worksAt"));
	}
}
