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

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.TestChatClient;
import com.sure.ai.rag.model.Document;

/**
 * LLM 实体/关系抽取器测试。
 */
public class LlmEntityRelationExtractorTest {

	/** 始终抛异常的对话客户端。 */
	private static AiClient throwingClient() {
		return new AiClient() {
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
	}

	private LlmEntityRelationExtractor extractorWith(AiClient client) {
		return LlmEntityRelationExtractor.builder()
				.chatClient(client).model("test-model").build();
	}

	@Test
	public void testExtractionProducesEntitiesRelationsAndSourceMeta() {
		String reply = "Alice | worksAt | Meta\nBeijing | locatedIn | China";
		LlmEntityRelationExtractor extractor = extractorWith(new TestChatClient(reply));
		KnowledgeGraph graph = new KnowledgeGraph();

		extractor.extractInto(Document.of("d1", "某段文本"), graph);

		assertEquals(4, graph.entityCount());
		assertEquals(2, graph.relationCount());
		assertEquals("d1", graph.getEntity("alice").metadata().get("sourceDocId"));
		assertTrue(graph.relationsOf("alice").stream()
				.anyMatch(r -> r.label().equals("worksAt") && r.targetId().equals("meta")));
	}

	@Test
	public void testParseToleranceSkipsBadLines() {
		String reply = String.join("\n",
				"坏行没有竖线",
				"",
				"   ",
				"| noSubject | x",
				"Bob | | noObject",
				"Alice | worksAt | Meta",
				"Charlie | knows | Dave");
		LlmEntityRelationExtractor extractor = extractorWith(new TestChatClient(reply));
		KnowledgeGraph graph = new KnowledgeGraph();

		extractor.extractInto(Document.of("d1", "文本"), graph);

		assertEquals(4, graph.entityCount());
		assertEquals(2, graph.relationCount());
	}

	@Test
	public void testLlmExceptionDoesNotThrowNorWrite() {
		LlmEntityRelationExtractor extractor = extractorWith(throwingClient());
		KnowledgeGraph graph = new KnowledgeGraph();

		extractor.extractInto(Document.of("d1", "文本"), graph);

		assertEquals(0, graph.entityCount());
		assertEquals(0, graph.relationCount());
	}
}
