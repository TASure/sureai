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

import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.InMemoryVectorStore;

/**
 * 进程内向量存储测试。
 */
public class InMemoryVectorStoreTest {

	@Test
	public void testAddAndSearch() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		store.add(Vector.of("a", new float[] {1, 0, 0}, "文档A"));
		store.add(Vector.of("b", new float[] {0, 1, 0}, "文档B"));
		store.add(Vector.of("c", new float[] {0, 0, 1}, "文档C"));
		assertEquals(3, store.size());

		List<SimilaritySearchResult> results = store.similaritySearch(new float[] {1, 0, 0}, 1);
		assertEquals(1, results.size());
		assertEquals("a", results.get(0).id());
		assertEquals("文档A", results.get(0).text());
		assertTrue(results.get(0).score() > 0.99);

		// topK 限制与降序
		results = store.similaritySearch(new float[] {1, 1, 0}, 2);
		assertEquals(2, results.size());
		assertTrue(results.get(0).score() >= results.get(1).score());
	}

	@Test
	public void testMinScoreFilter() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		store.add(Vector.of("a", new float[] {1, 0}, "A"));
		store.add(Vector.of("b", new float[] {0, 1}, "B"));
		List<SimilaritySearchResult> results =
				store.similaritySearch(new float[] {1, 0}, 10, 0.9);
		assertEquals(1, results.size());
		assertEquals("a", results.get(0).id());
	}

	@Test
	public void testDeleteClearAndOverwrite() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		store.add(Vector.of("a", new float[] {1, 0}, "旧文本"));
		store.add(Vector.of("a", new float[] {1, 0}, "新文本"));
		assertEquals(1, store.size());
		assertTrue(store.delete("a"));
		assertFalse(store.delete("a"));
		assertEquals(0, store.size());
		store.add(Vector.of("x", new float[] {1}, "X"));
		store.clear();
		assertEquals(0, store.size());
	}

	@Test
	public void testAddAll() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		List<Vector> batch = new java.util.ArrayList<>();
		batch.add(Vector.of("a", new float[] {1, 0}, "A"));
		batch.add(null);
		batch.add(Vector.of("b", new float[] {0, 1}, "B"));
		store.addAll(batch);
		assertEquals(2, store.size());
	}

	@Test
	public void testZeroNormVector() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		store.add(Vector.of("a", new float[] {0, 0}, "零向量"));
		store.add(Vector.of("b", new float[] {1, 0}, "正常向量"));
		List<SimilaritySearchResult> results =
				store.similaritySearch(new float[] {1, 0}, 10);
		// 零向量相似度为 0，不会排在前面
		assertEquals("b", results.get(0).id());
	}

	@Test
	public void testMetadataPreserved() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		store.add(Vector.of("a", new float[] {1}, "A", Map.of("source", "s1")));
		List<SimilaritySearchResult> results = store.similaritySearch(new float[] {1}, 1);
		assertEquals("s1", results.get(0).metadata().get("source"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidTopK() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		store.similaritySearch(new float[] {1}, 0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testNullVectorRejected() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		store.add(null);
	}

	@Test
	public void testEmptyEmbeddingRejected() {
		InMemoryVectorStore store = new InMemoryVectorStore();
		try {
			store.add(Vector.of("a", new float[0], "空向量"));
			assertFalse("应拒绝空向量", true);
		} catch (IllegalArgumentException expected) {
			assertTrue(true);
		}
	}
}
