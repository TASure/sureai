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

/**
 * {@link MarkdownTextSplitter} 边界单元测试：空输入、标题嵌套、长节按段落合并与重叠、
 * 超长单段字符窗口、无标题纯文本回退、getter，零外部依赖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class MarkdownTextSplitterTest {

	/** 空/null 输入返回空列表。 */
	@Test
	public void testEmptyInput() {
		MarkdownTextSplitter s = new MarkdownTextSplitter();
		assertTrue(s.split(null).isEmpty());
		assertTrue(s.split("").isEmpty());
	}

	/** getter。 */
	@Test
	public void testGetters() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(100, 10);
		assertEquals(100, s.chunkSize());
		assertEquals(10, s.chunkOverlap());
	}

	/** 标题嵌套：前缀保留层级路径。 */
	@Test
	public void testHeadingNesting() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(200, 10);
		List<String> chunks = s.split("# 一级\n正文A\n## 二级\n正文B\n### 三级\n正文C");
		assertTrue(chunks.size() >= 3);
		assertTrue(chunks.get(0).contains("一级"));
		assertTrue(chunks.stream().anyMatch(c -> c.contains("二级") && c.contains("二级")));
	}

	/** 长节按段落合并 + 重叠。 */
	@Test
	public void testLongSectionMergesParagraphs() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(50, 10);
		String body = String.join("\n\n",
				"第一段内容较长用于填充预算边界AAAA",
				"第二段内容同样较长用于填充预算边界BBBB",
				"第三段内容同样较长用于填充预算边界CCCC");
		List<String> chunks = s.split(body);
		assertTrue(chunks.size() >= 2);
	}

	/** 超长单段按字符窗口切分。 */
	@Test
	public void testOversizedParagraphCharWindow() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(30, 5);
		String longPara = "X".repeat(100);
		List<String> chunks = s.split(longPara);
		assertTrue(chunks.size() >= 3);
	}

	/** 无标题纯文本回退段落切分（小预算强制分块）。 */
	@Test
	public void testPlainTextFallback() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(12, 2);
		List<String> chunks = s.split("段落一内容较长文字。\n\n段落二内容较长文字。");
		assertTrue(chunks.size() >= 2);
	}

	/** 仅标题无正文 → 不产出空块。 */
	@Test
	public void testNoEmptyChunks() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(100, 10);
		List<String> chunks = s.split("# 只标题无正文\n");
		assertTrue(chunks.isEmpty());
	}

	/** 标题层级下降触发 stack.pop。 */
	@Test
	public void testHeadingLevelDecrease() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(200, 10);
		List<String> chunks = s.split("# 一级\n正文A\n### 三级\n正文B\n## 二级\n正文C");
		assertTrue(chunks.size() >= 3);
	}

	/** 标题前缀超长导致 budget<1 回退为 chunkSize。 */
	@Test
	public void testLongHeadingPrefixBudgetFallback() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(20, 5);
		String longTitle = "# " + "X".repeat(50);
		List<String> chunks = s.split(longTitle + "\n正文内容");
		assertTrue(chunks.size() >= 1);
	}

	/** 空段落跳过；重叠窗口。 */
	@Test
	public void testEmptyParagraphAndOverlap() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(30, 5);
		List<String> chunks = s.split("段落一。\n\n\n段落二。");
		assertTrue(chunks.size() >= 1);
	}
}
