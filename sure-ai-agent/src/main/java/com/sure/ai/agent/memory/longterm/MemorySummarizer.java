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
 * 对话摘要器：把一段对话消息压缩为一段自然语言摘要。
 *
 * <p>用于把过长的历史压缩后存入长期记忆，避免记忆条目膨胀。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
@FunctionalInterface
public interface MemorySummarizer {

	/**
	 * 生成摘要。
	 *
	 * @param messages 对话消息（按时间正序）
	 * @return 摘要文本
	 */
	String summarize(List<ChatMessage> messages);
}
