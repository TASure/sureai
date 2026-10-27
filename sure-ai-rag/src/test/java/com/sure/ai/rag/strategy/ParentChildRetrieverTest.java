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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.splitter.FixedSizeTextSplitter;
import com.sure.ai.rag.splitter.ParentChildSplitter;
import com.sure.ai.rag.store.InMemoryVectorStore;
import com.sure.ai.rag.store.VectorStore;

/**
 * 父子分块与父子检索测试。
 */
public class ParentChildRetrieverTest {

	/** 固定向量向量化器（用于可控的相似度测试）。 */
	private static EmbeddingProvider fixedProvider(float[] vector) {
		return new EmbeddingProvider() {
			@Override
			public float[] embed(String text) {
				return vector.clone();
			}

			@Override
			public List<float[]> embedAll(List<String> texts) {
				return texts.stream().map(t -> vector.clone()).toList();
			}
		};
	}

	@Test
	public void testSplitProducesParentsAndChildrenWithParentId() {
		ParentChildSplitter splitter = new ParentChildSplitter(
				new FixedSizeTextSplitter(40, 0), new FixedSizeTextSplitter(15, 0));
		String text = "苹果是一种常见水果，苹果树生长在果园里。"
				+ "汽车是一种交通工具，电动车使用电池驱动。";
		ParentChildSplitter.ParentChunks chunks = splitter.split("src", text);

		assertFalse(chunks.parents().isEmpty());
		assertFalse(chunks.children().isEmpty());
		// 每个子块都带 parentId
		for (Document child : chunks.children()) {
			assertTrue(child.metadata().containsKey(ParentChildSplitter.META_PARENT_ID));
		}
		// parentId 指向某个父块 id
		List<String> parentIds = chunks.parents().stream().map(Document::id).toList();
		for (Document child : chunks.children()) {
			assertTrue(parentIds.contains(child.metadata().get(ParentChildSplitter.META_PARENT_ID)));
		}
	}

	@Test
	public void testChildHitReturnsParentFullText() {
		// 手工构造父子结构
		String parent0Text = "苹果是一种常见水果。苹果树生长在果园里，秋季结果。";
		Document parent0 = Document.of("src#p0", parent0Text);
		Document child0 = Document.of("src#p0#c0", "苹果是一种常见水果。",
				Map.of(ParentChildSplitter.META_PARENT_ID, "src#p0"));

		VectorStore childStore = new InMemoryVectorStore();
		float[] v = { 1f, 0f, 0f };
		childStore.add(Vector.of(child0.id(), v, child0.text(), child0.metadata()));

		Map<String, Document> parentIndex = new HashMap<>();
		parentIndex.put(parent0.id(), parent0);

		ParentChildRetriever retriever = ParentChildRetriever.builder()
				.childStore(childStore)
				.embeddingProvider(fixedProvider(v))
				.parentIndex(parentIndex)
				.build();

		List<Document> result = retriever.retrieve("任意查询", 5);
		assertEquals(1, result.size());
		// 返回的是父块全文，而非子块
		assertEquals(parent0Text, result.get(0).text());
		assertFalse(child0.text().equals(result.get(0).text()));
	}

	@Test
	public void testSameParentMultipleChildrenDedup() {
		String parent0Text = "父块全文很长，包含两个子块。";
		Document parent0 = Document.of("src#p0", parent0Text);
		Document child1 = Document.of("src#p0#c0", "子块一",
				Map.of(ParentChildSplitter.META_PARENT_ID, "src#p0"));
		Document child2 = Document.of("src#p0#c1", "子块二",
				Map.of(ParentChildSplitter.META_PARENT_ID, "src#p0"));
		Document child3 = Document.of("src#p1#c0", "另一父块的子块",
				Map.of(ParentChildSplitter.META_PARENT_ID, "src#p1"));

		VectorStore childStore = new InMemoryVectorStore();
		float[] v = { 1f, 0f, 0f };
		childStore.add(Vector.of(child1.id(), v, child1.text(), child1.metadata()));
		childStore.add(Vector.of(child2.id(), v, child2.text(), child2.metadata()));
		childStore.add(Vector.of(child3.id(), new float[] { 0f, 1f, 0f },
				child3.text(), child3.metadata()));

		Map<String, Document> parentIndex = new HashMap<>();
		parentIndex.put(parent0.id(), parent0);
		parentIndex.put("src#p1", Document.of("src#p1", "另一个父块全文"));

		ParentChildRetriever retriever = ParentChildRetriever.builder()
				.childStore(childStore)
				.embeddingProvider(fixedProvider(v))
				.parentIndex(parentIndex)
				.minScore(0.5)
				.build();

		List<Document> result = retriever.retrieve("查询", 5);
		// child1 与 child2 都命中同一父块 → 只返回一次
		assertEquals(1, result.size());
		assertEquals(parent0Text, result.get(0).text());
	}
}
