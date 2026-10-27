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

package com.sure.ai.framework.memory;

import java.util.List;

import com.sure.ai.model.ChatMessage;

/**
 * 会话记忆：在多轮对话之间累积历史消息，供声明式代理在每次请求时注入上下文。
 *
 * <p>与 {@code sure-ai-agent} 的 {@code ConversationMemory} 语义对齐，但本接口独立于
 * agent 模块，使 framework 仅依赖 core 即可工作。实现需保证：</p>
 * <ul>
 *   <li>{@link #add(ChatMessage)} 按时间顺序追加；</li>
 *   <li>{@link #history()} 返回时间正序（最早→最新）的不可变快照；</li>
 *   <li>可自行决定窗口淘汰策略。</li>
 * </ul>
 *
 * @author sureai
 * @since 2.5.0
 */
public interface ChatMemory {

	/**
	 * 追加一条消息到记忆末尾。
	 *
	 * @param message 消息（非 null）
	 */
	void add(ChatMessage message);

	/**
	 * 当前历史（时间正序，从最早到最新）。
	 *
	 * @return 不可变消息列表快照
	 */
	List<ChatMessage> history();

	/**
	 * 清空全部历史。
	 */
	void clear();

	/**
	 * 当前记忆条数。
	 *
	 * @return 条数
	 */
	int size();
}
