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
 * 记忆提取器：从一段对话中提炼出值得跨会话长期记住的条目。
 *
 * <p>这是“长期记忆”的策略核心：决定哪些对话内容沉淀为记忆、沉淀成什么样。
 * 默认实现见 {@link DefaultMemoryExtractor}；可替换为基于 LLM 的抽取。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
@FunctionalInterface
public interface MemoryExtractor {

	/**
	 * 从对话消息中提取长期记忆条目。
	 *
	 * @param messages  本轮对话消息（按时间正序，非 null）
	 * @param sessionId 会话标识（可 null，写入元数据）
	 * @return 记忆条目列表（可能为空，不返回 null）
	 */
	List<MemoryEntry> extract(List<ChatMessage> messages, String sessionId);
}
