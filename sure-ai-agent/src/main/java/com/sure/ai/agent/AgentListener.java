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

package com.sure.ai.agent;

import com.sure.ai.model.ToolCall;

/**
 * Agent 事件回调监听器。
 *
 * <p>所有方法均为 default 空实现，使用方按需覆盖关心的事件。
 * 编排器在关键节点同步回调，可用于日志、追踪、进度展示、指标采集。</p>
 *
 * <p>回调链顺序（单轮工具调用）：</p>
 * <pre>
 *   onThought  →  onToolCall  →  onToolResult  →  …  →  onFinish
 *   任一节点异常 → onError
 * </pre>
 *
 * @author sureai
 * @since 0.3.0
 */
public interface AgentListener {

	/**
	 * 模型给出思考文本（非工具调用的助手内容）。
	 *
	 * @param thought 思考文本
	 */
	default void onThought(String thought) {
	}

	/**
	 * 模型请求调用工具。
	 *
	 * @param toolCall 工具调用记录
	 */
	default void onToolCall(ToolCall toolCall) {
	}

	/**
	 * 工具执行完成（无论成功失败，result 均为回灌模型的文本）。
	 *
	 * @param toolCall 工具调用记录
	 * @param result   回灌文本
	 */
	default void onToolResult(ToolCall toolCall, String result) {
	}

	/**
	 * 编排结束，模型给出最终答案。
	 *
	 * @param finalAnswer 最终答案文本
	 */
	default void onFinish(String finalAnswer) {
	}

	/**
	 * 工具执行异常（不中断编排，异常信息已回灌模型）。
	 *
	 * @param error 异常
	 */
	default void onError(Throwable error) {
	}
}
