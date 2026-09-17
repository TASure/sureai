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

import com.sure.ai.rag.splitter.FixedSizeTextSplitter;
import com.sure.ai.rag.splitter.TextSplitter;

/**
 * {@link FixedSizeTextSplitter} 测试。
 */
public class FixedSizeTextSplitterTest {

	@Test
	public void testFixedSizeChunks() {
		TextSplitter splitter = new FixedSizeTextSplitter(10, 0);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 50; i++) {
			sb.append('a');
		}
		List<String> chunks = splitter.split(sb.toString());
		assertEquals(5, chunks.size());
		for (String chunk : chunks) {
			assertEquals(10, chunk.length());
		}
	}

	@Test
	public void testOverlap() {
		TextSplitter splitter = new FixedSizeTextSplitter(20, 5);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 60; i++) {
			sb.append((char) ('a' + (i % 26)));
		}
		List<String> chunks = splitter.split(sb.toString());
		assertTrue(chunks.size() >= 2);
		// 相邻分块应有 5 字符重叠（步长 15）
		String first = chunks.get(0);
		String second = chunks.get(1);
		String overlap = first.substring(first.length() - 5);
		assertTrue(second, second.startsWith(overlap));
	}

	@Test
	public void testLastChunk() {
		TextSplitter splitter = new FixedSizeTextSplitter(10, 0);
		List<String> chunks = splitter.split("abcdefg");
		assertEquals(1, chunks.size());
		assertEquals("abcdefg", chunks.get(0));
	}

	@Test
	public void testEmptyText() {
		TextSplitter splitter = new FixedSizeTextSplitter();
		assertTrue(splitter.split(null).isEmpty());
		assertTrue(splitter.split("").isEmpty());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testValidation() {
		new FixedSizeTextSplitter(100, 100);
	}
}
