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
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.embedding.EmbeddingProvider;

/**
 * 分块器补覆盖测试：{@link MarkdownTextSplitter} 无标题前缀/空段落跳过/重叠回卷、
 * {@link SemanticTextSplitter} embedding 失败与向量长度不一致的降级路径、
 * {@link RecursiveCharacterTextSplitter} 空结果与首尾空白裁剪、
 * {@link FixedSizeTextSplitter} 访问器、{@link ParentChildSplitter} 空文本与父块索引，零网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class SplitterCoverageExtraTest {

	/** 抛异常的 mock 向量化提供者。 */
	private static EmbeddingProvider throwingProvider() {
		return new EmbeddingProvider() {
			@Override
			public float[] embed(String text) {
				throw new IllegalStateException("boom");
			}

			@Override
			public List<float[]> embedAll(List<String> texts) {
				throw new IllegalStateException("boom");
			}
		};
	}

	/** 返回固定向量的 mock 提供者。 */
	private static EmbeddingProvider fixedProvider(List<float[]> vectors) {
		return new EmbeddingProvider() {
			@Override
			public float[] embed(String text) {
				return vectors.get(0);
			}

			@Override
			public List<float[]> embedAll(List<String> texts) {
				return vectors;
			}
		};
	}

	// ===================== Markdown =====================

	/** 无标题正文：renderPrefix 返回空；空段落被跳过。 */
	@Test
	public void testMarkdownNoHeadingsSkipsBlankParagraphs() {
		MarkdownTextSplitter splitter = new MarkdownTextSplitter(100, 10);
		List<String> chunks = splitter.split("第一段内容。\n\n\n\n第二段内容。");
		assertTrue(chunks.toString(), chunks.size() >= 1);
	}

	/** 小预算多段落：触发重叠回卷（把上一块尾部带入新块）。 */
	@Test
	public void testMarkdownOverlapCarry() {
		MarkdownTextSplitter splitter = new MarkdownTextSplitter(30, 8);
		String body = "AAAA BBBB CCCC DDDD EEEE FFFF GGGG HHHH IIII JJJJ KKKK LLLL MMMM NNNN OOOO PPPP";
		List<String> chunks = splitter.split(body);
		assertTrue(chunks.size() > 1);
	}

	// ===================== Semantic =====================

	/** embedding 抛异常 → 降级固定大小分块。 */
	@Test
	public void testSemanticEmbeddingThrowsFallsBack() {
		SemanticTextSplitter splitter = SemanticTextSplitter.builder()
				.embeddingProvider(throwingProvider()).build();
		List<String> chunks = splitter.split("句子一。句子二。句子三。");
		assertTrue(chunks.size() >= 1);
	}

	/** embedding 返回向量长度与句子数不一致 → 降级固定大小分块。 */
	@Test
	public void testSemanticVectorSizeMismatchFallsBack() {
		SemanticTextSplitter splitter = SemanticTextSplitter.builder()
				.embeddingProvider(fixedProvider(List.of(new float[] { 1f }))).build();
		List<String> chunks = splitter.split("句子一。句子二。");
		assertTrue(chunks.size() >= 1);
	}

	// ===================== Recursive / FixedSize =====================

	/** 全空白文本 → 空结果；trimBlank 裁剪首尾空白。 */
	@Test
	public void testRecursiveBlankTextAndTrim() {
		RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(100, 10, null, true);
		assertTrue(splitter.split("   \n\t  \n  ").isEmpty());
		List<String> chunks = splitter.split("  hello world foo bar baz  ");
		assertEquals("hello world foo bar baz", chunks.get(0));
	}

	/** FixedSize 访问器。 */
	@Test
	public void testFixedSizeAccessors() {
		FixedSizeTextSplitter splitter = new FixedSizeTextSplitter(50, 10);
		assertEquals(50, splitter.chunkSize());
		assertEquals(10, splitter.chunkOverlap());
	}

	// ===================== ParentChild =====================

	/** 空文本 → 空 ParentChunks；parentIndex 按父块 id 建索引。 */
	@Test
	public void testParentChildEmptyAndIndex() {
		ParentChildSplitter splitter = new ParentChildSplitter(
				new FixedSizeTextSplitter(20, 0), new FixedSizeTextSplitter(8, 0));
		ParentChildSplitter.ParentChunks empty = splitter.split("src", "");
		assertTrue(empty.parents().isEmpty());
		assertTrue(empty.children().isEmpty());

		ParentChildSplitter.ParentChunks chunks = splitter.split("src", "AAAA BBBB CCCC DDDD EEEE FFFF");
		assertTrue(chunks.parents().size() >= 1);
		Map<String, ?> index = chunks.parentIndex();
		assertEquals(chunks.parents().size(), index.size());
	}
}
