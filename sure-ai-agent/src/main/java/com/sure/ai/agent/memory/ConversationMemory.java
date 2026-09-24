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

package com.sure.ai.agent.memory;

import java.util.List;

import com.sure.ai.model.ChatMessage;

/**
 * 会话记忆：在多轮对话之间累积历史消息，供编排器在每次请求时注入上下文。
 *
 * <p>典型用法：把同一个 {@code ConversationMemory} 实例注入 ReActAgent /
 * PlanExecuteAgent，编排器会在构造请求时把 {@link #history()} 注入到
 * baseRequest 模板消息之后、当前用户消息之前，并在一轮结束后把
 * 用户消息与助手最终答案追加进来。</p>
 *
 * <p>实现需保证：</p>
 * <ul>
 *   <li>{@link #add(ChatMessage)} 按时间顺序追加；</li>
 *   <li>{@link #history()} 返回时间正序的不可变快照；</li>
 *   <li>实现可自行决定窗口淘汰策略（如环形窗口）。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.1.0
 */
public interface ConversationMemory {

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
