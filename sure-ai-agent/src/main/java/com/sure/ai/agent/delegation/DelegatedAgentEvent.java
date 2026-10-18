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

import java.util.List;

import com.sure.ai.agent.event.AgentEvent;

/**
 * 委派冒泡事件包装：把一个子 Agent 原始 {@link AgentEvent} 包上委派路径元数据。
 *
 * <p>用于流式事件通道（{@code AgentEventPublisher}）：子 Agent 产生的原始事件不改变
 * 既有结构（公共 API 只增不改），而是由本包装附加来源 agentId、委派深度与祖先链，
 * 订阅者据此区分事件来自父本体还是某个被委派子 Agent。</p>
 *
 * @param delegatee     被包装的子 Agent 原始事件
 * @param sourceAgentId 产生该事件的子 Agent 标识
 * @param depth         该事件所处的委派深度
 * @param ancestorIds   祖先 Agent id 快照（不含 sourceAgentId）
 * @author sureai
 * @since 2.9.0
 */
public record DelegatedAgentEvent(AgentEvent delegatee, String sourceAgentId, int depth,
		List<String> ancestorIds) implements AgentEvent {

	/**
	 * 紧凑构造：防御性拷贝祖先列表。
	 *
	 * @param delegatee     原始事件
	 * @param sourceAgentId 来源 agentId
	 * @param depth         深度
	 * @param ancestorIds   祖先链
	 */
	public DelegatedAgentEvent {
		ancestorIds = ancestorIds == null ? List.of() : List.copyOf(ancestorIds);
	}

	@Override
	public String type() {
		return this.delegatee.type();
	}

	@Override
	public long timestampEpochMs() {
		return this.delegatee.timestampEpochMs();
	}

	/**
	 * 便捷包装工厂。
	 *
	 * @param delegatee     原始事件
	 * @param sourceAgentId 来源 agentId
	 * @param depth         深度
	 * @param ancestorIds   祖先链
	 * @return 包装事件
	 */
	public static DelegatedAgentEvent wrap(AgentEvent delegatee, String sourceAgentId,
			int depth, List<String> ancestorIds) {
		return new DelegatedAgentEvent(delegatee, sourceAgentId, depth, ancestorIds);
	}
}
