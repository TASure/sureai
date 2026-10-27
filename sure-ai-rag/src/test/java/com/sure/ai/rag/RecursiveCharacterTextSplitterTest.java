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

import org.junit.Test;

import com.sure.ai.rag.splitter.RecursiveCharacterTextSplitter;
import com.sure.ai.rag.splitter.TextSplitter;

/**
 * 递归字符分块器测试。
 */
public class RecursiveCharacterTextSplitterTest {

	@Test
	public void testSplitByParagraphs() {
		TextSplitter splitter = new RecursiveCharacterTextSplitter(10, 2, null, true);
		String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
		List<String> chunks = splitter.split(text);
		assertEquals(3, chunks.size());
		assertTrue(chunks.get(0).contains("第一段内容"));
		assertTrue(chunks.get(1).contains("第二段内容"));
		assertTrue(chunks.get(2).contains("第三段内容"));
	}

	@Test
	public void testSplitLongTextIntoMultipleChunks() {
		TextSplitter splitter = new RecursiveCharacterTextSplitter(20, 0, null, true);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 100; i++) {
			sb.append("这是一个用于测试分块的句子。");
		}
		List<String> chunks = splitter.split(sb.toString());
		assertTrue(chunks.size() > 1);
		for (String chunk : chunks) {
			assertFalse(chunk.isEmpty());
		}
	}

	@Test
	public void testChunkOverlap() {
		TextSplitter splitter = new RecursiveCharacterTextSplitter(60, 20, null, true);
		String text = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 60 个 A
				+ "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"; // 60 个 B
		List<String> chunks = splitter.split(text);
		assertTrue(chunks.size() >= 2);
		// 后一块开头应包含前一块末尾的内容（重叠生效）
		assertTrue(chunks.get(1).startsWith(chunks.get(0).substring(chunks.get(0).length() - 20)));
	}

	@Test
	public void testEmptyAndBlankText() {
		TextSplitter splitter = new RecursiveCharacterTextSplitter(100, 10, null, true);
		assertEquals(0, splitter.split(null).size());
		assertEquals(0, splitter.split("").size());
		assertEquals(0, splitter.split("  \n\t ").size());
	}

	@Test
	public void testSplitToDocuments() {
		TextSplitter splitter = new RecursiveCharacterTextSplitter(9, 2, null, true);
		List<com.sure.ai.rag.model.Document> docs =
				splitter.splitToDocuments("doc-1", "第一段。\n\n第二段。");
		assertEquals(2, docs.size());
		assertEquals("doc-1#0", docs.get(0).id());
		assertEquals("doc-1#1", docs.get(1).id());
	}

	@Test
	public void testCustomSeparator() {
		TextSplitter splitter = new RecursiveCharacterTextSplitter(200, 0,
				List.of(";"), true);
		List<String> chunks = splitter.split("aa;bb;cc");
		assertEquals(1, chunks.size());
		assertEquals("aa;bb;cc", chunks.get(0));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidOverlap() {
		new RecursiveCharacterTextSplitter(100, 100, null, true);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidChunkSize() {
		new RecursiveCharacterTextSplitter(0, 0, null, true);
	}

	@Test
	public void testCreateDefault() {
		RecursiveCharacterTextSplitter splitter = RecursiveCharacterTextSplitter.createDefault();
		assertEquals(RecursiveCharacterTextSplitter.DEFAULT_CHUNK_SIZE, splitter.chunkSize());
		assertEquals(RecursiveCharacterTextSplitter.DEFAULT_CHUNK_OVERLAP,
				splitter.chunkOverlap());
	}
}
