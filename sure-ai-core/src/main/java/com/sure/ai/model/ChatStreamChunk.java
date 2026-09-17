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

package com.sure.ai.model;

import java.util.List;

/**
 * 流式对话分片。
 *
 * @param id          响应 ID
 * @param role        角色（首片可能非空）
 * @param deltaText   增量文本
 * @param toolCalls   增量工具调用
 * @param finishReason 结束原因
 * @author sureai
 * @since 0.1.0
 */
public record ChatStreamChunk(String id, Role role, String deltaText,
		List<ToolCall> toolCalls, String finishReason) {

	/**
	 * 紧凑构造器：防御性拷贝 toolCalls。
	 */
	public ChatStreamChunk {
		toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
	}

	/**
	 * 静态工厂。
	 *
	 * @param id          响应 ID
	 * @param role        角色
	 * @param deltaText   增量文本
	 * @param toolCalls   增量工具调用
	 * @param finishReason 结束原因
	 * @return 分片
	 */
	public static ChatStreamChunk of(String id, Role role, String deltaText,
			List<ToolCall> toolCalls, String finishReason) {
		return new ChatStreamChunk(id, role, deltaText, toolCalls, finishReason);
	}
}
