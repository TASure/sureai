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
 * MarkdownTextSplitter 重叠分支补覆盖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class SplitterGraphWinsExtraTest {

	/** MarkdownTextSplitter：小窗口 + 重叠触发重叠追加分支。 */
	@Test
	public void testMarkdownSplitterOverlapBranch() {
		MarkdownTextSplitter s = new MarkdownTextSplitter(50, 10);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			sb.append("Paragraph number ").append(i).append(" with some content. ");
		}
		List<String> chunks = s.split(sb.toString());
		assertTrue(chunks.size() > 1);
	}
}
