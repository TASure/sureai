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
 * LLM 输出增量事件（预留）。
 *
 * <p>当前 ReActAgent 走非流式 {@code chat()}，不产生 token 级增量；该事件为将来接入
 * 流式客户端时预留，桥接器不会主动发出。</p>
 *
 * @param agentId           编排器标识
 * @param delta             增量文本片段
 * @param timestampEpochMs  事件时间戳
 * @author sureai
 * @since 1.7.0
 */
public record TokenDeltaEvent(String agentId, String delta, long timestampEpochMs)
		implements AgentEvent {

	@Override
	public String type() {
		return "token.delta";
	}
}
