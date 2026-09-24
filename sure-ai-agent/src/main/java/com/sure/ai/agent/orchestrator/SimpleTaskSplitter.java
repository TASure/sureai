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

package com.sure.ai.agent.orchestrator;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则式任务拆分器：按段落 / 句子 / 固定字数切分，不依赖模型。
 *
 * <p>三种策略：</p>
 * <ul>
 *   <li>{@link SplitStrategy#PARAGRAPH}：按空行（{@code \n\n}）切分，默认策略；</li>
 *   <li>{@link SplitStrategy#SENTENCE}：按中英文句末标点切分并过滤空串；</li>
 *   <li>{@link SplitStrategy#EVEN_COUNT}：按字符数平均切成 {@code count} 段。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class SimpleTaskSplitter implements TaskSplitter {

	/** 拆分策略。 */
	public enum SplitStrategy {
		/** 按空行切分。 */
		PARAGRAPH,
		/** 按句末标点切分。 */
		SENTENCE,
		/** 按字符数平均切分。 */
		EVEN_COUNT
	}

	private static final int DEFAULT_COUNT = 3;

	private final SplitStrategy strategy;
	private final int count;

	/**
	 * 默认构造：PARAGRAPH 策略。
	 */
	public SimpleTaskSplitter() {
		this(SplitStrategy.PARAGRAPH, DEFAULT_COUNT);
	}

	/**
	 * 指定策略构造（EVEN_COUNT 时使用默认段数）。
	 *
	 * @param strategy 拆分策略
	 */
	public SimpleTaskSplitter(SplitStrategy strategy) {
		this(strategy, DEFAULT_COUNT);
	}

	/**
	 * 全参构造。
	 *
	 * @param strategy 拆分策略
	 * @param count    子任务数量（仅 EVEN_COUNT 使用）
	 */
	public SimpleTaskSplitter(SplitStrategy strategy, int count) {
		this.strategy = strategy == null ? SplitStrategy.PARAGRAPH : strategy;
		this.count = Math.max(1, count);
	}

	@Override
	public List<String> split(String task) {
		if (task == null || task.isEmpty()) {
			return List.of();
		}
		return switch (this.strategy) {
			case SENTENCE -> splitSentence(task);
			case EVEN_COUNT -> splitEven(task);
			default -> splitParagraph(task);
		};
	}

	private List<String> splitParagraph(String task) {
		List<String> out = new ArrayList<>();
		for (String seg : task.split("\n\n")) {
			String trimmed = seg.trim();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	private List<String> splitSentence(String task) {
		List<String> out = new ArrayList<>();
		for (String seg : task.split("[。！？.!?]")) {
			String trimmed = seg.trim();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	private List<String> splitEven(String task) {
		int len = task.length();
		int size = (len + this.count - 1) / this.count;
		List<String> out = new ArrayList<>();
		for (int i = 0; i < this.count; i++) {
			int from = i * size;
			if (from >= len) {
				break;
			}
			int to = Math.min(from + size, len);
			out.add(task.substring(from, to));
		}
		return out;
	}
}
