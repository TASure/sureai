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
package com.sure.ai.rag.coverage;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.splitter.MarkdownTextSplitter;

/**
 * MarkdownTextSplitter 边界：charWindow step 兜底（L232）+ 无标题纯文本（L256）。
 *
 * @author sureai
 * @since 2.6.0
 */
public class MarkdownSplitterEdgeExtraTest {

	/** 小 chunkSize + 大 chunkOverlap + 长标题 → budget <= chunkOverlap 触发 step=budget 兜底。 */
	@Test
	public void testCharWindowStepFallback() {
		// chunkSize=50, chunkOverlap=40 → step = budget - 40 可能 <= 0
		MarkdownTextSplitter s = new MarkdownTextSplitter(50, 40);
		String md = "## Very Long Heading Title That Takes Up Space\n\n"
				+ "This is a body paragraph that is longer than the budget window and will be split into character windows. "
				+ "It has enough text to exceed the fifty character budget multiple times over here. "
				+ "Adding even more content to make sure the windows kick in properly now.";
		List<String> chunks = s.split(md);
		assertTrue(chunks.size() > 1);
	}

	/** 无标题纯文本 → renderPrefix 空栈返回空字符串。 */
	@Test
	public void testNoHeadingPureText() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(100, 10);
		String md = "Just some plain text without any headings at all. "
				+ "It should be split into paragraphs and returned as chunks.";
		List<String> chunks = s.split(md);
		assertTrue(!chunks.isEmpty());
	}
}
