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

package com.sure.ai.ingest.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.model.Document;

/**
 * {@link HtmlTextParser} 测试。
 */
public class HtmlTextParserTest {

	private final HtmlTextParser parser = new HtmlTextParser();

	@Test
	public void testStripTags() {
		String html = "<html><head><title>T</title></head><body><h1>Hi</h1><p>There</p></body></html>";
		List<Document> docs = parser.parse(html.getBytes(StandardCharsets.UTF_8), "a.html", meta());
		assertEquals(1, docs.size());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("Hi"));
		assertTrue(text, text.contains("There"));
		assertTrue(text, !text.contains("<"));
	}

	@Test
	public void testRemoveScriptStyle() {
		String html = "<html><style>.x{color:red}</style><body><p>real</p>"
				+ "<script>alert(1)</script></body></html>";
		List<Document> docs = parser.parse(html.getBytes(StandardCharsets.UTF_8), "a.html", meta());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("real"));
		assertTrue(text, !text.contains("alert"));
		assertTrue(text, !text.contains("color"));
	}

	@Test
	public void testEntities() {
		String html = "<p>&amp; &lt; &gt; &quot; &#39; &#65;</p>";
		List<Document> docs = parser.parse(html.getBytes(StandardCharsets.UTF_8), "a.html", meta());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("&"));
		assertTrue(text, text.contains("<"));
		assertTrue(text, text.contains("A"));
	}

	@Test
	public void testEmptyReturnsEmpty() {
		assertTrue(parser.parse("".getBytes(StandardCharsets.UTF_8), "a.html", meta()).isEmpty());
	}

	@Test
	public void testStaticExtract() {
		assertEquals("hello", HtmlTextParser.extractPlainText("<div>hello</div>"));
	}

	/**
	 * 基础元数据。
	 *
	 * @return 元数据
	 */
	private static Map<String, String> meta() {
		return new HashMap<>();
	}
}
