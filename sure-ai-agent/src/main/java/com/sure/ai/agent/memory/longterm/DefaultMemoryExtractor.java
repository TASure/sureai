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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.model.ChatMessage;

/**
 * 默认记忆提取器（零依赖、确定性）。
 *
 * <p>提取规则：</p>
 * <ul>
 *   <li>跳过 system / tool 角色消息与空白内容；</li>
 *   <li>每条长度 ≥ {@code minContentLength} 的 USER 消息 → 一条
 *       “用户说：…”记忆（捕捉用户关键指令/偏好）；</li>
 *   <li>最后一条非空白 ASSISTANT 消息 → 一条“助手答：…”记忆（捕捉最终结论）。</li>
 * </ul>
 *
 * <p>所有条目元数据写入 {@code role} 与 {@code sessionId}。
 * 生产环境可替换为基于 LLM 的结构化抽取。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class DefaultMemoryExtractor implements MemoryExtractor {

	/** 默认最小正文长度（过短的用户输入不沉淀）。 */
	public static final int DEFAULT_MIN_CONTENT_LENGTH = 3;

	/** 最小正文长度。 */
	private final int minContentLength;

	/**
	 * 使用默认阈值构造。
	 */
	public DefaultMemoryExtractor() {
		this(DEFAULT_MIN_CONTENT_LENGTH);
	}

	/**
	 * 构造。
	 *
	 * @param minContentLength 触发沉淀的最小正文长度
	 */
	public DefaultMemoryExtractor(int minContentLength) {
		if (minContentLength < 1) {
			throw new IllegalArgumentException("minContentLength must be >= 1");
		}
		this.minContentLength = minContentLength;
	}

	@Override
	public List<MemoryEntry> extract(List<ChatMessage> messages, String sessionId) {
		List<MemoryEntry> result = new ArrayList<>();
		if (messages == null || messages.isEmpty()) {
			return result;
		}
		ChatMessage lastAssistant = null;
		for (ChatMessage m : messages) {
			if (m == null || m.role() == null) {
				continue;
			}
			String content = m.content();
			if (content == null || content.isBlank()) {
				continue;
			}
			switch (m.role()) {
				case USER -> {
					if (content.strip().length() >= this.minContentLength) {
						result.add(entry("用户说：" + content.strip(), "user", sessionId));
					}
				}
				case ASSISTANT -> lastAssistant = m;
				default -> {
					// system / tool：不沉淀
				}
			}
		}
		if (lastAssistant != null && !lastAssistant.content().isBlank()) {
			result.add(entry("助手答：" + lastAssistant.content().strip(), "assistant", sessionId));
		}
		return result;
	}

	/**
	 * 构造一条带元数据的记忆条目。
	 */
	private static MemoryEntry entry(String content, String role, String sessionId) {
		Map<String, String> meta = new HashMap<>();
		meta.put("role", role);
		if (sessionId != null && !sessionId.isBlank()) {
			meta.put("sessionId", sessionId);
		}
		return MemoryEntry.of(content, meta);
	}
}
