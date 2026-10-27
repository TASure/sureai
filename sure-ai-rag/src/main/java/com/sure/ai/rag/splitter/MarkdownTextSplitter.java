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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sure.tool.lang.Assert;

/**
 * Markdown 分块器：按标题层级（{@code # ~ ######}）切分章节，保留标题路径作为分块上下文。
 *
 * <p>分块流程：</p>
 * <ol>
 *   <li>按 Markdown 标题行切分为「节」，每节记录其所属标题路径（如
 *       {@code # 一级\n## 二级\n} 前缀）；</li>
 *   <li>节文本不超过 {@code chunkSize} 时整块保留（标题上下文已包含）；</li>
 *   <li>节过长时按段落（空行分隔）合并至接近 {@code chunkSize}，并带
 *       {@code chunkOverlap} 重叠；单段仍超长则按字符窗口兜底；</li>
 *   <li>无标题纯文本回退到段落 + 字符大小切分。</li>
 * </ol>
 *
 * <p>每个分块均携带其标题路径前缀，检索命中后可还原文档结构上下文。
 * 遵守 {@link TextSplitter} 约定：空文本返回空列表、剔除空白块、不返回 null。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public class MarkdownTextSplitter implements TextSplitter {

	/** 默认块大小（字符数） */
	public static final int DEFAULT_CHUNK_SIZE = 1000;
	/** 默认块间重叠（字符数） */
	public static final int DEFAULT_CHUNK_OVERLAP = 100;

	private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+(.+?)[ \\t#]*$");
	private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t]*\\n");

	private final int chunkSize;
	private final int chunkOverlap;

	/**
	 * 使用默认参数（块大小 1000、重叠 100）创建。
	 */
	public MarkdownTextSplitter() {
		this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
	}

	/**
	 * 创建 Markdown 分块器。
	 *
	 * @param chunkSize 目标块大小（字符数），必须大于 0
	 * @param chunkOverlap 块间重叠（字符数），必须大于等于 0 且小于 chunkSize
	 */
	public MarkdownTextSplitter(int chunkSize, int chunkOverlap) {
		Assert.isTrue(chunkSize > 0, "chunkSize 必须大于 0，实际为 {}", chunkSize);
		Assert.isTrue(chunkOverlap >= 0, "chunkOverlap 必须大于等于 0，实际为 {}", chunkOverlap);
		Assert.isTrue(chunkOverlap < chunkSize,
				"chunkOverlap 必须小于 chunkSize，实际 overlap={} size={}", chunkOverlap, chunkSize);
		this.chunkSize = chunkSize;
		this.chunkOverlap = chunkOverlap;
	}

	/**
	 * 返回块大小。
	 *
	 * @return 块大小
	 */
	public int chunkSize() {
		return chunkSize;
	}

	/**
	 * 返回块间重叠。
	 *
	 * @return 块间重叠
	 */
	public int chunkOverlap() {
		return chunkOverlap;
	}

	@Override
	public List<String> split(String text) {
		if (text == null || text.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> chunks = new ArrayList<>();
		Deque<Heading> stack = new ArrayDeque<>();
		String[] lines = text.split("\n", -1);
		StringBuilder body = new StringBuilder();
		String headingPrefix = "";

		for (String rawLine : lines) {
			Matcher matcher = HEADING.matcher(rawLine.stripLeading());
			if (matcher.matches()) {
				// 遇到新标题：先收尾上一节
				emitSection(chunks, headingPrefix, body.toString());
				body.setLength(0);
				int level = matcher.group(1).length();
				String title = matcher.group(2).strip();
				while (!stack.isEmpty() && stack.peek().level >= level) {
					stack.pop();
				}
				stack.push(new Heading(level, title));
				headingPrefix = renderPrefix(stack);
			} else {
				if (body.length() > 0) {
					body.append('\n');
				}
				body.append(rawLine);
			}
		}
		// 收尾最后一节
		emitSection(chunks, headingPrefix, body.toString());
		return chunks;
	}

	/**
	 * 将一节（标题前缀 + 正文）切分为若干分块并加入结果。
	 *
	 * @param chunks 结果收集
	 * @param headingPrefix 标题路径前缀
	 * @param body 节正文
	 */
	private void emitSection(List<String> chunks, String headingPrefix, String body) {
		String trimmedBody = body == null ? "" : body.trim();
		if (headingPrefix.isEmpty() && trimmedBody.isEmpty()) {
			return;
		}
		int budget = chunkSize - headingPrefix.length();
		if (budget < 1) {
			budget = chunkSize;
		}
		List<String> bodyChunks = splitBody(trimmedBody, budget);
		for (String bodyChunk : bodyChunks) {
			String chunk = (headingPrefix + bodyChunk).trim();
			if (!chunk.isEmpty()) {
				chunks.add(chunk);
			}
		}
	}

	/**
	 * 按段落合并正文至预算长度，超长单段按字符窗口兜底。
	 *
	 * @param body 节正文
	 * @param budget 正文预算（字符数，不含标题前缀）
	 * @return 正文分块列表
	 */
	private List<String> splitBody(String body, int budget) {
		if (body.isEmpty()) {
			return new ArrayList<>(0);
		}
		String[] paragraphs = BLANK_LINE.split(body);
		List<String> bodyChunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String raw : paragraphs) {
			String paragraph = raw.trim();
			if (paragraph.isEmpty()) {
				continue;
			}
			if (paragraph.length() > budget) {
				flush(current, bodyChunks);
				current.setLength(0);
				bodyChunks.addAll(charWindow(paragraph, budget));
				continue;
			}
			if (current.length() == 0) {
				current.append(paragraph);
			} else if (current.length() + 2 + paragraph.length() <= budget) {
				current.append("\n\n").append(paragraph);
			} else {
				flush(current, bodyChunks);
				String previous = current.toString();
				current.setLength(0);
				int overlap = Math.min(chunkOverlap, previous.length());
				if (overlap > 0) {
					current.append(previous, previous.length() - overlap, previous.length());
				}
				current.append(paragraph);
			}
		}
		flush(current, bodyChunks);
		return bodyChunks;
	}

	/**
	 * 把当前累积块写入结果并清空。
	 *
	 * @param current 当前累积
	 * @param chunks 结果收集
	 */
	private static void flush(StringBuilder current, List<String> chunks) {
		String value = current.toString().trim();
		if (!value.isEmpty()) {
			chunks.add(value);
		}
		current.setLength(0);
	}

	/**
	 * 按固定字符窗口切分超长段落，带重叠。
	 *
	 * @param text 超长段落
	 * @param budget 窗口大小
	 * @return 字符窗口分块
	 */
	private List<String> charWindow(String text, int budget) {
		List<String> windows = new ArrayList<>();
		int step = budget - chunkOverlap;
		if (step <= 0) {
			step = budget;
		}
		int length = text.length();
		for (int start = 0; start < length; start += step) {
			int end = Math.min(start + budget, length);
			String window = text.substring(start, end).trim();
			if (!window.isEmpty()) {
				windows.add(window);
			}
			if (end == length) {
				break;
			}
		}
		return windows;
	}

	/**
	 * 渲染标题栈为前缀文本（自底向上，每行一个标题）。
	 *
	 * @param stack 标题栈（栈顶为最深层级）
	 * @return 标题前缀
	 */
	private static String renderPrefix(Deque<Heading> stack) {
		if (stack.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		Deque<Heading> reversed = new ArrayDeque<>(stack);
		while (!reversed.isEmpty()) {
			Heading heading = reversed.pollLast();
			sb.append(repeat('#', heading.level)).append(' ')
					.append(heading.title).append('\n');
		}
		return sb.toString();
	}

	/**
	 * 生成 n 个 '#' 字符。
	 *
	 * @param count 数量
	 * @return 字符串
	 */
	private static String repeat(char ch, int count) {
		char[] chars = new char[count];
		Arrays.fill(chars, ch);
		return new String(chars);
	}

	/**
	 * 标题栈元素：层级与标题文本。
	 *
	 * @param level 标题层级（1~6）
	 * @param title 标题文本
	 */
	private record Heading(int level, String title) {
	}
}
