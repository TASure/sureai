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

import java.util.ArrayList;
import java.util.List;

/**
 * 手写 Markdown 子集渲染器（v3.0.0，零第三方依赖，不引 commonmark 等）。
 *
 * <p>支持的语法：ATX 标题 {@code #~######}（自动生成锚点 id）、段落、围栏代码块
 * {@code ```lang}（mermaid 围栏原样输出到 {@code <pre class="mermaid">}）、行内代码、
 * 链接 {@code [text](url)}、图片、无序/有序列表（同一层级）、GFM 表格、引用块、
 * {@code **粗体**} / {@code *斜体*}、水平线 {@code ---}。正文中的 {@code < > &}
 * 自动转义，代码块与行内代码内原样。</p>
 *
 * <p>不支持（文档站覆盖不到的边界）：锚点别名、折叠标量、HTML 原样块、任务列表、
 * 定义列表、删除线。</p>
 *
 * @author sureai
 * @since 3.0.0
 */
public final class MarkdownRenderer {

	/** 行内代码占位符前缀（转义后保护 code 内容不被再次处理）。 */
	private static final char PLACEHOLDER = '\u0001';

	private MarkdownRenderer() {
		throw new AssertionError("No instances");
	}

	/**
	 * 渲染 Markdown 为 HTML 片段（不含页面外壳）。
	 *
	 * @param markdown 源文本
	 * @return HTML 片段
	 */
	public static String render(String markdown) {
		if (markdown == null) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		for (String line : markdown.split("\n", -1)) {
			lines.add(line);
		}
		StringBuilder html = new StringBuilder();
		int i = 0;
		while (i < lines.size()) {
			String line = lines.get(i);
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				i++;
				continue;
			}
			// 围栏代码块（含 mermaid）
			if (trimmed.startsWith("```")) {
				i = renderFence(lines, i, html);
				continue;
			}
			// ATX 标题
			if (trimmed.startsWith("#")) {
				int level = 0;
				while (level < trimmed.length() && level < 6 && trimmed.charAt(level) == '#') {
					level++;
				}
				if (level <= 6 && trimmed.length() > level
					&& Character.isWhitespace(trimmed.charAt(level))) {
					String title = trimmed.substring(level).trim();
					html.append("<h").append(level)
						.append(" id=\"").append(slug(title)).append("\">")
						.append(inline(title)).append("</h").append(level).append(">\n");
					i++;
					continue;
				}
			}
			// 水平线
			if (isHr(trimmed)) {
				html.append("<hr />\n");
				i++;
				continue;
			}
			// 表格：当前行以 | 开头且下一行为分隔行
			if (trimmed.startsWith("|") && i + 1 < lines.size()
				&& isTableSeparator(lines.get(i + 1).trim())) {
				i = renderTable(lines, i, html);
				continue;
			}
			// 引用块
			if (trimmed.startsWith(">")) {
				i = renderBlockquote(lines, i, html);
				continue;
			}
			// 列表
			if (isListLine(trimmed)) {
				i = renderList(lines, i, html);
				continue;
			}
			// 段落：收集到空行/块级开头为止；| 开头但非法表格的行也按段落消费，保证推进
			StringBuilder para = new StringBuilder();
			int start = i;
			while (i < lines.size() && !lines.get(i).trim().isEmpty()
				&& !isBlockStart(lines.get(i).trim())) {
				para.append(inline(lines.get(i))).append(' ');
				i++;
			}
			if (i == start && i < lines.size()) {
				// 当前行是块级开头但未被上层分支处理（如非法表格分隔行）：按段落消费一行
				para.append(inline(lines.get(i).trim())).append(' ');
				i++;
			}
			html.append("<p>").append(para.toString().trim()).append("</p>\n");
		}
		return html.toString();
	}

	private static int renderFence(List<String> lines, int i, StringBuilder html) {
		String open = lines.get(i).trim();
		String lang = open.substring(3).trim();
		i++;
		StringBuilder code = new StringBuilder();
		while (i < lines.size() && !lines.get(i).trim().startsWith("```")) {
			code.append(lines.get(i)).append('\n');
			i++;
		}
		if (i < lines.size()) {
			i++; // 闭围栏
		}
		if ("mermaid".equals(lang)) {
			html.append("<pre class=\"mermaid\">\n")
				.append(code).append("</pre>\n");
		} else {
			html.append("<pre><code");
			if (!lang.isEmpty()) {
				html.append(" class=\"language-").append(escape(lang)).append('"');
			}
			html.append(">\n").append(escape(code.toString())).append("</code></pre>\n");
		}
		return i;
	}

	private static int renderTable(List<String> lines, int i, StringBuilder html) {
		List<String[]> rows = new ArrayList<>();
		String[] header = splitCells(lines.get(i).trim());
		rows.add(header);
		i += 2; // 跳过表头与分隔行
		while (i < lines.size() && lines.get(i).trim().startsWith("|")) {
			rows.add(splitCells(lines.get(i).trim()));
			i++;
		}
		html.append("<table>\n<thead>\n<tr>");
		for (String cell : rows.get(0)) {
			html.append("<th>").append(inline(cell.trim())).append("</th>");
		}
		html.append("</tr>\n</thead>\n<tbody>\n");
		for (int r = 1; r < rows.size(); r++) {
			html.append("<tr>");
			for (String cell : rows.get(r)) {
				html.append("<td>").append(inline(cell.trim())).append("</td>");
			}
			html.append("</tr>\n");
		}
		html.append("</tbody>\n</table>\n");
		return i;
	}

	private static int renderBlockquote(List<String> lines, int i, StringBuilder html) {
		html.append("<blockquote>\n");
		while (i < lines.size() && lines.get(i).trim().startsWith(">")) {
			String content = lines.get(i).trim().substring(1).trim();
			html.append("<p>").append(inline(content)).append("</p>\n");
			i++;
		}
		html.append("</blockquote>\n");
		return i;
	}

	private static int renderList(List<String> lines, int i, StringBuilder html) {
		html.append("<ul>\n");
		while (i < lines.size() && isListLine(lines.get(i).trim())) {
			String trimmed = lines.get(i).trim();
			String item = trimmed.replaceFirst("^[-*+]\\s+|^\\d+[.)]\\s+", "");
			html.append("<li>").append(inline(item)).append("</li>\n");
			i++;
		}
		html.append("</ul>\n");
		return i;
	}

	/**
	 * 行内渲染：提取行内代码保护 → 转义 → 链接/图片/粗体/斜体替换 → 还原代码。
	 *
	 * @param text 行内文本
	 * @return HTML
	 */
	static String inline(String text) {
		List<String> codes = new ArrayList<>();
		StringBuilder scan = new StringBuilder();
		// 1) 提取 `code` 为占位符（内容原样不转义）
		boolean inCode = false;
		StringBuilder codeBuf = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '`') {
				if (inCode) {
					codes.add(codeBuf.toString());
					scan.append(PLACEHOLDER).append(codes.size() - 1).append(PLACEHOLDER);
					codeBuf.setLength(0);
					inCode = false;
				} else {
					inCode = true;
				}
			} else if (inCode) {
				codeBuf.append(c);
			} else {
				scan.append(c);
			}
		}
		if (inCode) {
			scan.append('`').append(codeBuf);
		}
		// 2) 转义剩余文本（链接 URL 中的 & 亦转义，属正确 HTML）
		String s = escape(scan.toString());
		// 3) 图片 / 链接 / 粗体 / 斜体替换（生成合法标签，不再二次转义）
		s = s.replaceAll(
			"!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)",
			"<img alt=\"$1\" src=\"$2\" />");
		s = s.replaceAll(
			"\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)",
			"<a href=\"$2\">$1</a>");
		s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
		s = s.replaceAll("(?<!\\*)\\*([^*\\s][^*]*)\\*(?!\\*)", "<em>$1</em>");
		// 4) 还原行内代码
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == PLACEHOLDER) {
				int start = i + 1;
				int end = s.indexOf(PLACEHOLDER, start);
				if (end > 0) {
					int idx = Integer.parseInt(s.substring(start, end));
					result.append("<code>").append(escape(codes.get(idx))).append("</code>");
					i = end;
					continue;
				}
			}
			result.append(c);
		}
		return result.toString();
	}

	/**
	 * 生成标题锚点 id（slug 化）。
	 *
	 * @param title 标题文本
	 * @return 锚点 id
	 */
	static String slug(String title) {
		StringBuilder sb = new StringBuilder();
		for (char c : title.toLowerCase().toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				sb.append(c);
			} else if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '-') {
				sb.append('-');
			}
		}
		return sb.toString();
	}

	private static boolean isHr(String trimmed) {
		if (trimmed.length() < 3) {
			return false;
		}
		char first = trimmed.charAt(0);
		if (first != '-' && first != '*' && first != '_') {
			return false;
		}
		for (char c : trimmed.toCharArray()) {
			if (c != first) {
				return false;
			}
		}
		return true;
	}

	private static boolean isTableSeparator(String trimmed) {
		if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) {
			return false;
		}
		String body = trimmed.substring(1, trimmed.length() - 1);
		for (String cell : body.split("\\|")) {
			String c = cell.trim();
			if (c.isEmpty() || !c.matches(":?-{3,}:?")) {
				return false;
			}
		}
		return true;
	}

	private static boolean isListLine(String trimmed) {
		return trimmed.matches("^[-*+]\\s+.*") || trimmed.matches("^\\d+[.)]\\s+.*");
	}

	private static boolean isBlockStart(String trimmed) {
		return trimmed.startsWith("```") || trimmed.startsWith("#")
			|| trimmed.startsWith(">") || trimmed.startsWith("|")
			|| isListLine(trimmed) || isHr(trimmed);
	}

	private static String[] splitCells(String line) {
		String body = line.trim();
		if (body.startsWith("|")) {
			body = body.substring(1);
		}
		if (body.endsWith("|")) {
			body = body.substring(0, body.length() - 1);
		}
		return body.split("\\|", -1);
	}

	static String escape(String text) {
		StringBuilder sb = new StringBuilder();
		for (char c : text.toCharArray()) {
			sb.append(escapeChar(c));
		}
		return sb.toString();
	}

	private static String escapeChar(char c) {
		return switch (c) {
			case '&' -> "&amp;";
			case '<' -> "&lt;";
			case '>' -> "&gt;";
			default -> String.valueOf(c);
		};
	}
}
