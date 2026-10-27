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
 * Agent 执行事件标记接口。
 *
 * <p>所有具体事件均为不可变 record，必须提供：</p>
 * <ul>
 *   <li>{@link #type()} —— 稳定的事件类型字符串（如 {@code "step.started"}），
 *       用于 SSE {@code event:} 字段与前端路由；</li>
 *   <li>{@link #timestampEpochMs()} —— 事件发生时刻（毫秒时间戳）。</li>
 * </ul>
 *
 * <p>事件由 {@link StreamingAgentListener} 从同步 {@code AgentListener} 回调桥接产生，
 * 经 {@link AgentEventPublisher} 广播给订阅者，可由 {@link AgentEventSseWriter}
 * 序列化为 SSE 帧。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public interface AgentEvent {

	/**
	 * 事件类型字符串。
	 *
	 * @return 稳定类型标识
	 */
	String type();

	/**
	 * 事件发生时间戳。
	 *
	 * @return 毫秒 epoch
	 */
	long timestampEpochMs();
}
