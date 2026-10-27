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

package com.sure.ai.docsite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 手写 Markdown 渲染器语法覆盖测试。
 */
public class MarkdownRendererTest {

	@Test
	public void rendersHeadingsWithAnchors() {
		String html = MarkdownRenderer.render("# 一级标题\n\n## 二级 标题\n");
		assertTrue(html.contains("<h1 id=\"一级标题\">一级标题</h1>"));
		assertTrue(html.contains("<h2 id=\"二级-标题\">二级 标题</h2>"));
	}

	@Test
	public void rendersParagraphAndInlineCode() {
		String html = MarkdownRenderer.render("使用 `code` 与 <b>转义</b> & 符号。");
		assertTrue(html.contains("<p>"));
		assertTrue(html.contains("<code>code</code>"));
		assertTrue(html.contains("&lt;b&gt;转义&lt;/b&gt;"));
		assertTrue(html.contains("&amp;"));
	}

	@Test
	public void rendersFenceWithLanguageAndEscapesContent() {
		String html = MarkdownRenderer.render("```java\nSystem.out.println(\"<a>\");\n```");
		assertTrue(html.contains("class=\"language-java\""));
		assertTrue(html.contains("&lt;a&gt;"));
		assertFalse(html.contains("<a>"));
	}

	@Test
	public void rendersMermaidFenceAsIs() {
		String md = "```mermaid\ngraph LR\n  A --> B\n```";
		String html = MarkdownRenderer.render(md);
		assertTrue(html.contains("<pre class=\"mermaid\">"));
		assertTrue(html.contains("graph LR"));
	}

	@Test
	public void rendersLinksAndImages() {
		String html = MarkdownRenderer.render("[官网](https://example.com) 与 ![logo](logo.png)");
		assertTrue(html.contains("<a href=\"https://example.com\">官网</a>"));
		assertTrue(html.contains("<img alt=\"logo\" src=\"logo.png\" />"));
	}

	@Test
	public void rendersBoldItalicAndHr() {
		String html = MarkdownRenderer.render("**粗体** 与 *斜体*\n\n---\n");
		assertTrue(html.contains("<strong>粗体</strong>"));
		assertTrue(html.contains("<em>斜体</em>"));
		assertTrue(html.contains("<hr />"));
	}

	@Test
	public void rendersUnorderedAndOrderedLists() {
		String html = MarkdownRenderer.render("- 甲\n- 乙\n\n1. 一\n2. 二\n");
		assertTrue(html.contains("<ul>"));
		assertTrue(html.contains("<li>甲</li>"));
		assertTrue(html.contains("<li>一</li>"));
	}

	@Test
	public void rendersTable() {
		String md = "| 列A | 列B |\n| --- | --- |\n| 1 | 2 |\n";
		String html = MarkdownRenderer.render(md);
		assertTrue(html.contains("<table>"));
		assertTrue(html.contains("<th>列A</th>"));
		assertTrue(html.contains("<td>1</td>"));
	}

	@Test
	public void rendersBlockquote() {
		String html = MarkdownRenderer.render("> 引用的内容");
		assertTrue(html.contains("<blockquote>"));
		assertTrue(html.contains("引用的内容"));
	}

	@Test
	public void rendersMultipleParagraphsSeparately() {
		String html = MarkdownRenderer.render("第一段。\n\n第二段。");
		assertEquals(2, countOccurrences(html, "<p>"));
	}

	@Test
	public void escapesAngleBracketsInCodeFence() {
		String html = MarkdownRenderer.render("```\nif (a < b) { }\n```");
		assertTrue(html.contains("if (a &lt; b) { }"));
	}

	@Test
	public void nullAndEmptyRenderToEmpty() {
		assertEquals("", MarkdownRenderer.render(null));
		assertEquals("", MarkdownRenderer.render(""));
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int idx = 0;
		while ((idx = haystack.indexOf(needle, idx)) >= 0) {
			count++;
			idx += needle.length();
		}
		return count;
	}
}
