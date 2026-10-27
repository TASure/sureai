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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.sure.tool.lang.Assert;

/**
 * 递归字符分块器：按分隔符优先级逐级切分，直至每块不超过目标长度。
 *
 * <p>参考 LangChain {@code RecursiveCharacterTextSplitter} 的思路实现：
 * 依次尝试段落、换行、句子标点、空格等分隔符递归切分，尽量保持语义边界；
 * 支持块间重叠（overlap）以避免切分点截断关键信息。纯 JDK 实现，零依赖。</p>
 *
 * <p>默认分隔符按优先级排列：{@code \n\n}（段落）、{@code \n}（换行）、
 * {@code 。！？}（中文句号/叹号/问号）、{@code .!?}（英文句点/叹号/问号）、
 * {@code ，,}（逗号）、空格、空串（兜底按字符切分）。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class RecursiveCharacterTextSplitter implements TextSplitter {

	/** 默认块大小（字符数） */
	public static final int DEFAULT_CHUNK_SIZE = 1000;
	/** 默认块间重叠（字符数） */
	public static final int DEFAULT_CHUNK_OVERLAP = 200;
	/** 默认分隔符优先级列表 */
	private static final List<String> DEFAULT_SEPARATORS = Arrays.asList(
			"\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", "，", ",", " ", "");

	private final int chunkSize;
	private final int chunkOverlap;
	private final List<String> separators;
	private final boolean keepSeparator;

	/**
	 * 创建分块器。
	 *
	 * @param chunkSize 目标块大小（字符数），必须大于 0
	 * @param chunkOverlap 块间重叠（字符数），必须大于等于 0 且小于 chunkSize
	 * @param separators 分隔符优先级列表（前高后低），空列表使用默认分隔符
	 * @param keepSeparator 是否将分隔符保留在分块末尾
	 */
	public RecursiveCharacterTextSplitter(int chunkSize, int chunkOverlap,
			List<String> separators, boolean keepSeparator) {
		Assert.isTrue(chunkSize > 0, "chunkSize 必须大于 0，实际为 {}", chunkSize);
		Assert.isTrue(chunkOverlap >= 0, "chunkOverlap 必须大于等于 0，实际为 {}", chunkOverlap);
		Assert.isTrue(chunkOverlap < chunkSize,
				"chunkOverlap 必须小于 chunkSize，实际 overlap={} size={}", chunkOverlap, chunkSize);
		this.chunkSize = chunkSize;
		this.chunkOverlap = chunkOverlap;
		this.separators = separators == null || separators.isEmpty()
				? DEFAULT_SEPARATORS
				: Collections.unmodifiableList(new ArrayList<>(separators));
		this.keepSeparator = keepSeparator;
	}

	/**
	 * 使用默认参数创建分块器（块大小 1000、重叠 200、保留分隔符）。
	 *
	 * @return 分块器
	 */
	public static RecursiveCharacterTextSplitter createDefault() {
		return new RecursiveCharacterTextSplitter(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP,
				null, true);
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
		List<String> chunks = splitText(text);
		if (chunks.isEmpty()) {
			return chunks;
		}
		List<String> result = new ArrayList<>(chunks.size());
		for (String chunk : chunks) {
			String trimmed = trimBlank(chunk);
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}

	/**
	 * 剔除分块首尾空白。
	 *
	 * @param text 分块
	 * @return 剔除首尾空白后的文本
	 */
	private String trimBlank(String text) {
		int start = 0;
		int end = text.length();
		while (start < end && Character.isWhitespace(text.charAt(start))) {
			start++;
		}
		while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(start, end);
	}

	/**
	 * 核心递归切分：优先用高优先级分隔符切分，块仍超长则降级到下一级分隔符。
	 *
	 * @param text 待切分文本（非空）
	 * @return 分块列表（未做空白过滤）
	 */
	private List<String> splitText(String text) {
		List<String> finals = new ArrayList<>();
		splitWithSeparator(text, 0, finals);
		return finals;
	}

	/**
	 * 使用指定优先级的分隔符切分文本。
	 *
	 * @param text 待切分文本
	 * @param separatorIndex 分隔符优先级下标
	 * @param finals 结果收集列表
	 */
	private void splitWithSeparator(String text, int separatorIndex, List<String> finals) {
		String separator = separators.get(separatorIndex);
		List<String> pieces = splitBy(text, separator);
		List<String> merged = mergePieces(pieces, separator);
		for (String piece : merged) {
			if (piece.length() > chunkSize && separatorIndex < separators.size() - 1) {
				// 仍超长：降级到下一优先级分隔符继续切分
				splitWithSeparator(piece, separatorIndex + 1, finals);
			} else {
				finals.add(piece);
			}
		}
	}

	/**
	 * 按分隔符切分文本；分隔符为空串时按字符切分。
	 *
	 * @param text 待切分文本
	 * @param separator 分隔符
	 * @return 切分片段
	 */
	private List<String> splitBy(String text, String separator) {
		List<String> pieces = new ArrayList<>();
		if (separator.isEmpty()) {
			for (int i = 0; i < text.length(); i++) {
				pieces.add(text.substring(i, i + 1));
			}
			return pieces;
		}
		int start = 0;
		int idx;
		while ((idx = text.indexOf(separator, start)) >= 0) {
			pieces.add(text.substring(start, idx));
			start = idx + separator.length();
		}
		pieces.add(text.substring(start));
		return pieces;
	}

	/**
	 * 合并过小的片段以逼近目标块大小，并在片段间恢复分隔符；再按 chunkSize 与 overlap 二次聚块。
	 *
	 * @param pieces 按分隔符切出的片段
	 * @param separator 分隔符
	 * @return 合并后的分块
	 */
	private List<String> mergePieces(List<String> pieces, String separator) {
		List<String> merged = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String piece : pieces) {
			if (current.length() == 0) {
				current.append(piece);
			} else if (current.length() + piece.length() + separator.length() <= chunkSize) {
				if (keepSeparator) {
					current.append(separator);
				}
				current.append(piece);
			} else {
				merged.add(current.toString());
				current = new StringBuilder(piece);
			}
		}
		if (current.length() > 0) {
			merged.add(current.toString());
		}
		return applyOverlap(merged);
	}

	/**
	 * 在相邻分块之间应用重叠：后一块开头补上前一块末尾的 overlap 个字符。
	 *
	 * @param chunks 无重叠的分块
	 * @return 应用重叠后的分块
	 */
	private List<String> applyOverlap(List<String> chunks) {
		if (chunkOverlap == 0 || chunks.size() < 2) {
			return chunks;
		}
		List<String> result = new ArrayList<>(chunks.size());
		result.add(chunks.get(0));
		for (int i = 1; i < chunks.size(); i++) {
			String previous = chunks.get(i - 1);
			String current = chunks.get(i);
			int overlap = Math.min(chunkOverlap, previous.length());
			String suffix = previous.substring(previous.length() - overlap);
			result.add(suffix + current);
		}
		return result;
	}
}
