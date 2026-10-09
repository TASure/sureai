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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sure.ai.ingest.DocumentLoader;
import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * HTML 文本提取器：JDK 手写正则剥离标签的最小实现。
 *
 * <p>提取流程：</p>
 * <ol>
 *   <li>移除 {@code <script>...</script>} 与 {@code <style>...</style>} 块（含内容）；</li>
 *   <li>移除所有 HTML 标签；</li>
 *   <li>解码常用 HTML 实体与数字实体；</li>
 *   <li>合并多余空白。</li>
 * </ol>
 *
 * <p><b>支持子集与限制</b>：JDK 21 无内置 HTML DOM 解析器，本实现基于正则，
 * 不处理复杂嵌套、畸形标签、JS 渲染页面与 CSS 布局还原，仅适合简单静态 HTML。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class HtmlTextParser implements DocumentParser {

	/** 支持的扩展名。 */
	private static final Set<String> EXTENSIONS = Set.of(".html", ".htm", ".xhtml");

	private static final Pattern SCRIPT_STYLE_BLOCK =
			Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1>",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	@Override
	public Set<String> extensions() {
		return EXTENSIONS;
	}

	@Override
	public List<Document> parse(byte[] content, String source, Map<String, String> metadata) {
		String html = new String(content, StandardCharsets.UTF_8);
		String text = extractPlainText(html).trim();
		if (text.isEmpty()) {
			return new ArrayList<>(0);
		}
		metadata.put(DocumentLoader.META_FORMAT, "html");
		List<Document> documents = new ArrayList<>(1);
		documents.add(Document.of(source, text, metadata));
		return documents;
	}

	/**
	 * 基础 HTML 去标签提取纯文本。
	 *
	 * @param html 原始 HTML
	 * @return 纯文本
	 */
	public static String extractPlainText(String html) {
		if (html == null || html.isEmpty()) {
			return "";
		}
		String noBlocks = SCRIPT_STYLE_BLOCK.matcher(html).replaceAll(" ");
		String noTags = HTML_TAG.matcher(noBlocks).replaceAll(" ");
		String decoded = decodeEntities(noTags);
		return WHITESPACE.matcher(decoded).replaceAll(" ").trim();
	}

	/**
	 * 解码常用 HTML 实体与数字实体。
	 *
	 * @param input 去标签后的文本
	 * @return 解码后文本
	 */
	private static String decodeEntities(String input) {
		String result = input;
		result = result.replace("&nbsp;", " ");
		result = result.replace("&quot;", "\"");
		result = result.replace("&#39;", "'");
		result = result.replace("&lt;", "<");
		result = result.replace("&gt;", ">");
		result = result.replace("&amp;", "&");
		Matcher matcher = NUMERIC_ENTITY.matcher(result);
		StringBuffer sb = new StringBuffer(result.length());
		while (matcher.find()) {
			boolean hex = "x".equals(matcher.group(1));
			int codePoint;
			try {
				codePoint = Integer.parseInt(matcher.group(2), hex ? 16 : 10);
			} catch (NumberFormatException e) {
				matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
				continue;
			}
			matcher.appendReplacement(sb,
					Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}
}
