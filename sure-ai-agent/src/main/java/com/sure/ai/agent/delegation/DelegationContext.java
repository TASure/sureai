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

import java.time.Duration;
import java.util.List;

/**
 * 单次委派的不可变上下文。
 *
 * <p>记录本次委派在调用链上的位置与附带信息，供事件冒泡、日志追踪与测试断言使用；
 * 不臆造用不到的字段。</p>
 *
 * @param chainId      调用链标识（同一顶层委派分支内全程不变，UUID）
 * @param depth        当前委派深度（根 = 0，每下传一层 +1）
 * @param sourceAgentId 被委派子 Agent 的稳定标识
 * @param parentTask   父 Agent 转交过来的任务摘要（调用参数中的任务描述）
 * @param ancestorIds  自根到当前父的祖先 Agent id 不可变快照（不含本次 source）
 * @param memoryPolicy  记忆共享策略
 * @param timeout      本次委派执行上限（null 表示不额外限制）
 * @author sureai
 * @since 2.9.0
 */
public record DelegationContext(String chainId, int depth, String sourceAgentId,
		String parentTask, List<String> ancestorIds, MemoryPolicy memoryPolicy,
		Duration timeout) {

	/**
	 * 紧凑构造：防御性拷贝祖先列表，补默认策略。
	 *
	 * @param chainId      调用链标识
	 * @param depth        深度
	 * @param sourceAgentId 子 Agent 标识
	 * @param parentTask   任务摘要
	 * @param ancestorIds  祖先 id 列表
	 * @param memoryPolicy 记忆策略（null 视为 {@link MemoryPolicy#ISOLATED}）
	 * @param timeout      执行上限（可 null）
	 */
	public DelegationContext {
		ancestorIds = ancestorIds == null ? List.of() : List.copyOf(ancestorIds);
		memoryPolicy = memoryPolicy == null ? MemoryPolicy.ISOLATED : memoryPolicy;
	}

	/**
	 * 追加下一层上下文：depth +1，把当前 source 并入祖先。
	 *
	 * @param nextAgentId  下一层子 Agent 标识
	 * @param nextTask     下一层任务摘要
	 * @return 新的子上下文（不可变）
	 */
	public DelegationContext child(String nextAgentId, String nextTask) {
		List<String> extended = new java.util.ArrayList<>(this.ancestorIds);
		extended.add(this.sourceAgentId);
		return new DelegationContext(this.chainId, this.depth + 1, nextAgentId,
			nextTask, extended, this.memoryPolicy, this.timeout);
	}
}
