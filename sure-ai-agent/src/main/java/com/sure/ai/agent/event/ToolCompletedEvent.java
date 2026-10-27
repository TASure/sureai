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

package com.sure.ai.agent.event;

/**
 * 工具完成事件（无论成功失败）。
 *
 * @param agentId           编排器标识
 * @param toolName          工具名
 * @param result            回灌模型的文本
 * @param success           是否成功（非错误前缀）
 * @param timestampEpochMs  事件时间戳
 * @author sureai
 * @since 1.7.0
 */
public record ToolCompletedEvent(String agentId, String toolName, String result,
		boolean success, long timestampEpochMs) implements AgentEvent {

	@Override
	public String type() {
		return "tool.completed";
	}
}
