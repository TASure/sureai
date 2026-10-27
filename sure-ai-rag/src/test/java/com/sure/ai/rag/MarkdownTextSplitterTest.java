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

import com.sure.ai.rag.splitter.MarkdownTextSplitter;
import com.sure.ai.rag.splitter.TextSplitter;

/**
 * {@link MarkdownTextSplitter} 测试。
 */
public class MarkdownTextSplitterTest {

	@Test
	public void testSplitByHeadings() {
		TextSplitter splitter = new MarkdownTextSplitter(1000, 100);
		String md = "# 标题1\n\n内容1\n\n## 标题2\n\n内容2";
		List<String> chunks = splitter.split(md);
		assertEquals(2, chunks.size());
		assertTrue(chunks.get(0), chunks.get(0).contains("标题1"));
		assertTrue(chunks.get(0), chunks.get(0).contains("内容1"));
		assertTrue(chunks.get(1), chunks.get(1).contains("标题2"));
		assertTrue(chunks.get(1), chunks.get(1).contains("内容2"));
	}

	@Test
	public void testHeadingContextPreserved() {
		TextSplitter splitter = new MarkdownTextSplitter(1000, 100);
		String md = "# 一级\n\n## 二级\n\n小节正文";
		List<String> chunks = splitter.split(md);
		assertEquals(1, chunks.size());
		// 分块应保留标题路径前缀
		assertTrue(chunks.get(0), chunks.get(0).contains("# 一级"));
		assertTrue(chunks.get(0), chunks.get(0).contains("## 二级"));
		assertTrue(chunks.get(0), chunks.get(0).contains("小节正文"));
	}

	@Test
	public void testLongSectionSplits() {
		TextSplitter splitter = new MarkdownTextSplitter(50, 10);
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 20; i++) {
			body.append("段落").append(i).append("内容文本。\n\n");
		}
		String md = "# 长节\n\n" + body;
		List<String> chunks = splitter.split(md);
		assertTrue(chunks.size() > 1);
		for (String chunk : chunks) {
			assertTrue(!chunk.isEmpty());
		}
		// 每个分块都应携带标题前缀
		for (String chunk : chunks) {
			assertTrue(chunk, chunk.contains("长节"));
		}
	}

	@Test
	public void testEmptyText() {
		TextSplitter splitter = new MarkdownTextSplitter();
		assertTrue(splitter.split(null).isEmpty());
		assertTrue(splitter.split("").isEmpty());
	}

	@Test
	public void testNoHeadings() {
		TextSplitter splitter = new MarkdownTextSplitter(30, 5);
		String text = "这是第一段。\n\n这是第二段。\n\n这是第三段。";
		List<String> chunks = splitter.split(text);
		assertTrue(chunks.size() >= 1);
		for (String chunk : chunks) {
			assertTrue(!chunk.isEmpty());
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void testChunkOverlapValidation() {
		new MarkdownTextSplitter(100, 100);
	}
}
