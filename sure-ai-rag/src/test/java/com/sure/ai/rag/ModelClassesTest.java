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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.rag.splitter.TextSplitter;
import com.sure.ai.rag.store.VectorStore;

/**
 * RAG 模型类与静态入口测试。
 */
public class ModelClassesTest {

	@Test
	public void testDocumentFactories() {
		Document doc = Document.of("id-1", "正文");
		assertEquals("id-1", doc.id());
		assertTrue(doc.metadata().isEmpty());

		Document withMeta = Document.of("id-2", "正文", Map.of("k", "v"));
		assertEquals("v", withMeta.metadata().get("k"));

		Document nullMeta = new Document("id-3", "正文", null);
		assertNotNull(nullMeta.metadata());
		assertTrue(nullMeta.metadata().isEmpty());
	}

	@Test
	public void testVectorFactoriesAndDefensiveCopy() {
		float[] embedding = new float[] {1, 2, 3};
		Vector vector = Vector.of("v1", embedding, "文本");
		// 外部数组变更不影响内部
		embedding[0] = 99;
		assertEquals(1.0f, vector.embedding()[0], 0.0001f);
		// 返回的也是副本
		vector.embedding()[0] = 42;
		assertEquals(1.0f, vector.embedding()[0], 0.0001f);

		Vector withMeta = Vector.of("v2", new float[] {1}, "文本", Map.of("a", "b"));
		assertEquals("b", withMeta.metadata().get("a"));

		Vector nullMeta = new Vector("v3", new float[] {1}, "文本", null);
		assertTrue(nullMeta.metadata().isEmpty());
	}

	@Test
	public void testSimilaritySearchResult() {
		float[] embedding = new float[] {1, 0};
		SimilaritySearchResult result = new SimilaritySearchResult("id", embedding,
				"文本", null, 0.85);
		embedding[0] = 5;
		assertEquals(1.0f, result.embedding()[0], 0.0001f);
		assertTrue(result.metadata().isEmpty());
		assertEquals(0.85, result.score(), 0.0001);
	}

	@Test
	public void testRagUtil() {
		TextSplitter splitter = RagUtil.splitter();
		assertNotNull(splitter);
		assertNotNull(RagUtil.splitter(100, 20));
		VectorStore store = RagUtil.inMemoryStore();
		assertNotNull(store);
		RagPipeline pipeline = RagUtil.pipeline(new TestChatClient("x"),
				new TestEmbeddingClient(), "chat", "embed");
		assertNotNull(pipeline);
		assertNotNull(RagUtil.pipelineBuilder());
	}
}
