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
import java.util.Collections;
import java.util.List;

import com.sure.tool.lang.Assert;

/**
 * 固定大小分块器：按固定字符大小滑动窗口切分，步长 = {@code chunkSize - chunkOverlap}。
 *
 * <p>实现简单、边界确定，适合对语义边界不敏感的场景（如均匀切块做向量化基线）。
 * 剔除空白块；最后一个分块不足 {@code chunkSize} 时保留（不丢弃）。
 * 纯 JDK 实现，零依赖。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public class FixedSizeTextSplitter implements TextSplitter {

	/** 默认块大小（字符数） */
	public static final int DEFAULT_CHUNK_SIZE = 1000;
	/** 默认块间重叠（字符数） */
	public static final int DEFAULT_CHUNK_OVERLAP = 100;

	private final int chunkSize;
	private final int chunkOverlap;

	/**
	 * 使用默认参数（块大小 1000、重叠 100）创建。
	 */
	public FixedSizeTextSplitter() {
		this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
	}

	/**
	 * 创建固定大小分块器。
	 *
	 * @param chunkSize 目标块大小（字符数），必须大于 0
	 * @param chunkOverlap 块间重叠（字符数），必须大于等于 0 且小于 chunkSize
	 */
	public FixedSizeTextSplitter(int chunkSize, int chunkOverlap) {
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
		int step = chunkSize - chunkOverlap;
		List<String> chunks = new ArrayList<>();
		int length = text.length();
		for (int start = 0; start < length; start += step) {
			int end = Math.min(start + chunkSize, length);
			String chunk = text.substring(start, end).trim();
			if (!chunk.isEmpty()) {
				chunks.add(chunk);
			}
			if (end == length) {
				break;
			}
		}
		return chunks;
	}
}
