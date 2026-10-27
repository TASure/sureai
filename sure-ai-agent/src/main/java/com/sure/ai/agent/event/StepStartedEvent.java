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
 * 步骤开始事件。
 *
 * @param agentId           编排器标识
 * @param iteration         迭代/步骤序号
 * @param description       步骤描述
 * @param timestampEpochMs  事件时间戳
 * @author sureai
 * @since 1.7.0
 */
public record StepStartedEvent(String agentId, int iteration, String description,
		long timestampEpochMs) implements AgentEvent {

	@Override
	public String type() {
		return "step.started";
	}
}
