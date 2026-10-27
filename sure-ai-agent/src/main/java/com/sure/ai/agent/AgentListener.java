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

import java.util.List;

import com.sure.ai.model.ToolCall;

/**
 * Agent 事件回调监听器。
 *
 * <p>所有方法均为 default 空实现，使用方按需覆盖关心的事件。
 * 编排器在关键节点同步回调，可用于日志、追踪、进度展示、指标采集。</p>
 *
 * <p>ReAct 回调链顺序（单轮工具调用）：</p>
 * <pre>
 *   onThought  →  onToolCall  →  onToolResult  →  …  →  onFinish
 *   任一节点异常 → onError
 * </pre>
 *
 * <p>Plan-and-Execute 额外回调链：</p>
 * <pre>
 *   onThought(计划原文) → onPlanGenerated(步骤列表)
 *       → onStepStart → (onToolCall/onToolResult)* → onStepComplete
 *       → …（逐步骤） → onFinish
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

	/**
	 * Plan-and-Execute：计划已生成并解析为有序步骤列表。
	 *
	 * <p>紧跟在 {@link #onThought(String)}（计划原文）之后触发。</p>
	 *
	 * @param steps 解析后的步骤文本列表（不可变快照，按执行顺序）
	 * @since 1.1.0
	 */
	default void onPlanGenerated(List<String> steps) {
	}

	/**
	 * Plan-and-Execute：开始执行某一步骤。
	 *
	 * @param index   步骤下标（从 0 开始）
	 * @param step    步骤描述文本
	 * @since 1.1.0
	 */
	default void onStepStart(int index, String step) {
	}

	/**
	 * Plan-and-Execute：某一步骤执行完成（含失败回退后的结果）。
	 *
	 * @param index  步骤下标（从 0 开始）
	 * @param result  该步骤产出的结果文本
	 * @since 1.1.0
	 */
	default void onStepComplete(int index, String result) {
	}
}
