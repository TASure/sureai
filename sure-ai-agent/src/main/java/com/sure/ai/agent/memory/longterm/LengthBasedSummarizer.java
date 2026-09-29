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

package com.sure.ai.agent.memory.longterm;

import java.util.List;

import com.sure.ai.model.ChatMessage;

/**
 * 基于长度的摘要器：按总字符数截断，保留首尾两段。
 *
 * <p>策略：把所有非空消息正文以换行拼接；若总长度不超过 {@code maxChars} 则原样返回；
 * 否则保留前半段 + 中间省略标记 + 后半段，使总长受控。
 * 这是零依赖下的确定性降级方案，生产环境可替换为基于 LLM 的摘要实现。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class LengthBasedSummarizer implements MemorySummarizer {

	/** 默认最大字符数。 */
	public static final int DEFAULT_MAX_CHARS = 500;

	/** 省略标记。 */
	private static final String ELLIPSIS = " …[截断]… ";

	/** 最大字符数。 */
	private final int maxChars;

	/**
	 * 使用默认上限构造。
	 */
	public LengthBasedSummarizer() {
		this(DEFAULT_MAX_CHARS);
	}

	/**
	 * 构造。
	 *
	 * @param maxChars 最大字符数（&ge;1）
	 */
	public LengthBasedSummarizer(int maxChars) {
		if (maxChars < 1) {
			throw new IllegalArgumentException("maxChars must be >= 1");
		}
		this.maxChars = maxChars;
	}

	@Override
	public String summarize(List<ChatMessage> messages) {
		StringBuilder sb = new StringBuilder();
		if (messages != null) {
			for (ChatMessage m : messages) {
				String c = m.content();
				if (c != null && !c.isBlank()) {
					if (sb.length() > 0) {
						sb.append('\n');
					}
					sb.append(c);
				}
			}
		}
		String full = sb.toString();
		if (full.length() <= this.maxChars) {
			return full;
		}
		int half = Math.max(1, (this.maxChars - ELLIPSIS.length()) / 2);
		String head = full.substring(0, half);
		String tail = full.substring(full.length() - half);
		return head + ELLIPSIS + tail;
	}
}
