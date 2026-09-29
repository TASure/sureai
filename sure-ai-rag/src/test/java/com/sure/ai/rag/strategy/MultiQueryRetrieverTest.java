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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.rewriter.QueryRewriter;

/**
 * Multi-Query 检索器（RRF 融合）测试。
 */
public class MultiQueryRetrieverTest {

	/** 记录子查询并按预设返回结果的底层检索器。 */
	private static final class StubRetriever implements Retriever {

		private final Map<String, List<Document>> responses;
		private final List<String> calledQueries = new ArrayList<>();

		StubRetriever(Map<String, List<Document>> responses) {
			this.responses = responses;
		}

		@Override
		public List<Document> retrieve(String query, int topK) {
			this.calledQueries.add(query);
			return this.responses.getOrDefault(query, new ArrayList<>());
		}
	}

	private static Document doc(String id) {
		return Document.of(id, "text-" + id);
	}

	@Test
	public void testEachSubQueryTriggersRetrievalAndRrfOrder() {
		// q1 -> [A, B]; q2 -> [B, C]
		Map<String, List<Document>> responses = new HashMap<>();
		responses.put("q1", new ArrayList<>(Arrays.asList(doc("A"), doc("B"))));
		responses.put("q2", new ArrayList<>(Arrays.asList(doc("B"), doc("C"))));
		StubRetriever bottom = new StubRetriever(responses);

		QueryRewriter rewriter = (query, count) -> Arrays.asList("q1", "q2");

		MultiQueryRetriever retriever = MultiQueryRetriever.builder()
				.retriever(bottom)
				.rewriter(rewriter)
				.queryCount(2)
				.rrfK(60.0)
				.build();

		List<Document> result = retriever.retrieve("original", 5);

		// 每个子查询都触发了检索
		assertEquals(2, bottom.calledQueries.size());
		assertTrue(bottom.calledQueries.contains("q1"));
		assertTrue(bottom.calledQueries.contains("q2"));

		// RRF: B 在 q1 排第2、q2 排第1 => 1/61+1/62 最高；
		// A 仅 q1 排第1 => 1/61；C 仅 q2 排第2 => 1/62。顺序应为 B,A,C
		assertEquals(3, result.size());
		assertEquals("B", result.get(0).id());
		assertEquals("A", result.get(1).id());
		assertEquals("C", result.get(2).id());
	}

	@Test
	public void testDedupSameId() {
		// 两个子查询都返回同一个文档 D
		Map<String, List<Document>> responses = new HashMap<>();
		responses.put("q1", new ArrayList<>(Arrays.asList(doc("D"))));
		responses.put("q2", new ArrayList<>(Arrays.asList(doc("D"))));
		StubRetriever bottom = new StubRetriever(responses);

		QueryRewriter rewriter = (query, count) -> Arrays.asList("q1", "q2");

		MultiQueryRetriever retriever = MultiQueryRetriever.builder()
				.retriever(bottom).rewriter(rewriter).queryCount(2)
				.build();

		List<Document> result = retriever.retrieve("original", 5);
		assertEquals(1, result.size());
		assertEquals("D", result.get(0).id());
	}

	@Test
	public void testRewriterFailureStillWorksWithOriginalQuery() {
		// 改写失败：返回单元素原 query
		Map<String, List<Document>> responses = new HashMap<>();
		responses.put("original", new ArrayList<>(Arrays.asList(doc("X"))));
		StubRetriever bottom = new StubRetriever(responses);

		QueryRewriter rewriter = (query, count) -> List.of(query);

		MultiQueryRetriever retriever = MultiQueryRetriever.builder()
				.retriever(bottom).rewriter(rewriter).queryCount(3)
				.build();

		List<Document> result = retriever.retrieve("original", 5);
		assertEquals(1, bottom.calledQueries.size());
		assertEquals("original", bottom.calledQueries.get(0));
		assertEquals(1, result.size());
		assertEquals("X", result.get(0).id());
	}
}
