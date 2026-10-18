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

package com.sure.ai.agent.delegation;

import com.sure.ai.agent.AgentListener;
import com.sure.tool.lang.Assert;

/**
 * 子 Agent 工厂：委派发生时按给定事件接收器现场构造子 {@link AgentRunner}。
 *
 * <p>ReActAgent / PlanExecuteAgent 的 listener 在构造期注入、不可替换，而委派层需要把
 * 冒泡监听器接进子 Agent；因此不在装配期固化子 Agent，而是每次委派时由工厂用传入的
 * {@code sink} 构造子 Agent。固定预置的子 Agent 可用 {@link #fixed(AgentRunner)} 包装，
 * 工厂忽略 sink。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
@FunctionalInterface
public interface ChildAgentFactory {

	/**
	 * 用冒泡事件接收器构造子 Agent。
	 *
	 * @param sink 委派层提供的冒泡监听器（子 Agent 的内部事件经它转发给父监听者）
	 * @return 子 Agent 运行句柄
	 */
	AgentRunner create(AgentListener sink);

	/**
	 * 固定子 Agent 工厂：忽略 sink，直接返回预置句柄。
	 *
	 * @param runner 预置子 Agent
	 * @return 工厂
	 */
	static ChildAgentFactory fixed(AgentRunner runner) {
		Assert.notNull(runner, "runner must not be null");
		return sink -> runner;
	}
}
