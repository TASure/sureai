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

/**
 * 委派链路的会话记忆共享策略。
 *
 * <p>ReActAgent / PlanExecuteAgent 的 {@link com.sure.ai.agent.memory.ConversationMemory}
 * 在构造期注入、运行期不可替换，委派层无法在运行时为子 Agent 重新绑定记忆，
 * 因此本策略只表达<b>装配意图与上下文标注</b>，实际共享由调用方在构造父子
 * Agent 时决定是否传入同一个记忆实例。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public enum MemoryPolicy {

	/**
	 * 隔离（默认）：父子 Agent 各自持有独立会话记忆，互不读写对方历史。
	 *
	 * <p>默认选择隔离的理由：委派是「把一个子任务交给专职 Agent」，子 Agent 的
	 * 中间推理历史不应污染父 Agent 的多轮上下文，也避免不同专职 Agent 的事实
	 * 互相串味；需要跨轮延续时由调用方显式装配共享记忆。</p>
	 */
	ISOLATED,

	/**
	 * 共享：调用方在构造父子 Agent 时传入同一个
	 * {@link com.sure.ai.agent.memory.ConversationMemory} 实例，双方读写同一历史。
	 */
	SHARED
}
