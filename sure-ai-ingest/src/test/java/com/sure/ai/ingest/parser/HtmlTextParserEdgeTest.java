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

import org.junit.Test;

/**
 * {@link HtmlTextParser} 边界测试：非法数字实体（十进制模式下出现十六进制字符）
 * 触发 NumberFormatException 宽容分支，以及静态 extractPlainText 的 null/空入参。
 */
public class HtmlTextParserEdgeTest {

	private final HtmlTextParser parser = new HtmlTextParser();

	/**
	 * &#ABC; 无 x 前缀却含十六进制字符，十进制 parseInt 抛异常后原样保留实体。
	 */
	@Test
	public void testMalformedNumericEntityPreserved() {
		String html = "<p>x &#ABC; y</p>";
		var docs = parser.parse(html.getBytes(StandardCharsets.UTF_8), "a.html", new HashMap<>());
		assertEquals(1, docs.size());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("x"));
		assertTrue(text, text.contains("y"));
	}

	/**
	 * 十六进制数字实体 &#x41; 还原为 'A'。
	 */
	@Test
	public void testHexNumericEntity() {
		assertEquals("A", HtmlTextParser.extractPlainText("<p>&#x41;</p>"));
	}

	/**
	 * extractPlainText：null 与空串均返回空串。
	 */
	@Test
	public void testExtractNullAndEmpty() {
		assertEquals("", HtmlTextParser.extractPlainText(null));
		assertEquals("", HtmlTextParser.extractPlainText(""));
	}
}
