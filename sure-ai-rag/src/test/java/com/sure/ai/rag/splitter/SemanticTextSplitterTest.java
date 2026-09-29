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
package com.sure.ai.rag.splitter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.embedding.EmbeddingProvider;

/**
 * 语义分块器测试。
 */
public class SemanticTextSplitterTest {

	private static final float[] VEC_A = { 1f, 0f, 0f };
	private static final float[] VEC_B = { 0f, 1f, 0f };

	/** 按句子内容归属主题 A/B 返回可控向量。 */
	private static EmbeddingProvider topicProvider() {
		return new EmbeddingProvider() {
			@Override
			public float[] embed(String text) {
				return text != null && text.contains("B主题") ? VEC_B.clone() : VEC_A.clone();
			}

			@Override
			public List<float[]> embedAll(List<String> texts) {
				return texts.stream().map(this::embed).toList();
			}
		};
	}

	@Test
	public void testSplitsAtTopicBoundary() {
		SemanticTextSplitter splitter = SemanticTextSplitter.builder()
				.embeddingProvider(topicProvider())
				.build();
		String text = "A主题句一。A主题句二。B主题句一。B主题句二。";
		List<String> chunks = splitter.split(text);

		// 在 A→B 切换处断开为两块
		assertEquals(2, chunks.size());
		assertTrue(chunks.get(0).contains("A主题句一"));
		assertTrue(chunks.get(0).contains("A主题句二"));
		assertTrue(chunks.get(1).contains("B主题句一"));
		assertTrue(chunks.get(1).contains("B主题句二"));
		// 块内句子保持原顺序
		assertTrue(chunks.get(0).indexOf("A主题句一") < chunks.get(0).indexOf("A主题句二"));
	}

	@Test
	public void testThresholdParameterEffect() {
		String text = "A主题句一。A主题句二。B主题句一。B主题句二。";

		// 阈值 -1：永远不切分 → 整块
		SemanticTextSplitter noCut = SemanticTextSplitter.builder()
				.embeddingProvider(topicProvider()).threshold(-1.0).build();
		assertEquals(1, noCut.split(text).size());

		// 阈值 1.0：仅在相似度 <1 处切分（跨主题 0 < 1）→ 两块
		SemanticTextSplitter cut = SemanticTextSplitter.builder()
				.embeddingProvider(topicProvider()).threshold(1.0).build();
		assertEquals(2, cut.split(text).size());
	}

	@Test
	public void testEmbeddingFailureFallsBackToFixedSize() {
		EmbeddingProvider throwing = new EmbeddingProvider() {
			@Override
			public float[] embed(String text) {
				throw new RuntimeException("embedding down");
			}

			@Override
			public List<float[]> embedAll(List<String> texts) {
				throw new RuntimeException("embedding down");
			}
		};
		SemanticTextSplitter splitter = SemanticTextSplitter.builder()
				.embeddingProvider(throwing).build();
		// 不抛异常，降级为固定大小分块
		List<String> chunks = splitter.split("这是一段用于降级测试的中文文本，长度不长。");
		assertTrue(chunks != null && !chunks.isEmpty());
	}

	@Test
	public void testEmptyTextReturnsEmpty() {
		SemanticTextSplitter splitter = SemanticTextSplitter.createDefault(topicProvider());
		assertTrue(splitter.split("").isEmpty());
		assertTrue(splitter.split(null).isEmpty());
	}
}
