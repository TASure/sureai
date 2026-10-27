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
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.retriever.KeywordRetriever;

/**
 * {@link KeywordRetriever}（BM25）测试。
 */
public class KeywordRetrieverTest {

	private KeywordRetriever sample() {
		List<Document> docs = List.of(
				Document.of("doc1", "the quick brown fox"),
				Document.of("doc2", "lazy dog sleeps soundly"),
				Document.of("doc3", "quick fox and lazy dog"));
		return new KeywordRetriever(docs);
	}

	@Test
	public void testBm25Scoring() {
		KeywordRetriever retriever = sample();
		List<Document> results = retriever.retrieve("quick fox", 3);
		assertEquals(3, results.size());
		// doc1 与 doc3 命中 quick+fox，doc2 不含
		String top = results.get(0).id();
		assertTrue("doc1 或 doc3 应排第一，实际 " + top,
				top.equals("doc1") || top.equals("doc3"));
		// doc2 应排在最后
		assertEquals("doc2", results.get(2).id());
	}

	@Test
	public void testTopK() {
		KeywordRetriever retriever = sample();
		List<Document> results = retriever.retrieve("quick", 1);
		assertEquals(1, results.size());
	}

	@Test
	public void testEmptyDocuments() {
		KeywordRetriever retriever = new KeywordRetriever(List.of());
		assertTrue(retriever.retrieve("quick", 5).isEmpty());
		assertTrue(retriever.retrieveWithScores("quick", 5).isEmpty());
	}

	@Test
	public void testNoMatch() {
		KeywordRetriever retriever = sample();
		// 查询词不匹配任何文档：仍返回文档但得分 0，按原始顺序
		List<Document> results = retriever.retrieve("zzzqqqq", 3);
		assertEquals(3, results.size());
		List<KeywordRetriever.Scored> scored = retriever.retrieveWithScores("zzzqqqq", 3);
		for (KeywordRetriever.Scored s : scored) {
			assertEquals(0.0, s.score(), 1e-9);
		}
	}

	@Test
	public void testLengthNormalization() {
		// 短文档与长文档均只命中一个词：长文档因长度归一化得分应更低
		Document shortDoc = Document.of("short", "apple");
		StringBuilder longText = new StringBuilder("apple");
		for (int i = 0; i < 50; i++) {
			longText.append(" fillerword").append(i);
		}
		Document longDoc = Document.of("long", longText.toString());
		KeywordRetriever retriever = new KeywordRetriever(List.of(shortDoc, longDoc));
		List<KeywordRetriever.Scored> scored = retriever.retrieveWithScores("apple", 2);
		double shortScore = scored.get(0).document().id().equals("short")
				? scored.get(0).score() : scored.get(1).score();
		double longScore = scored.get(0).document().id().equals("long")
				? scored.get(0).score() : scored.get(1).score();
		assertTrue("短文档应因长度归一化而得分更高：short="
				+ shortScore + " long=" + longScore, shortScore > longScore);
	}
}
