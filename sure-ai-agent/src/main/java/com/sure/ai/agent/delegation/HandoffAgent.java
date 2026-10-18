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

import com.sure.tool.lang.Assert;

/**
 * Handoff（任务交接）包装器：把控制权整体移交给目标 Agent。
 *
 * <p><b>与 {@link AgentAsTool} 的语义差异：</b></p>
 * <ul>
 *   <li>{@code AgentAsTool}：委派后<b>父 Agent 继续</b>，子答案只是一条 observation，
 *       父模型可继续调用其他工具、继续推理；</li>
 *   <li>{@code Handoff}：任务<b>交接</b>，目标 Agent 的答案即最终答案，父 Agent
 *       不再把结果当作可继续推理的观察、也不再发起后续工具调用——控制权移交。</li>
 * </ul>
 *
 * <p>本类是最小交接实现：包装目标 {@link AgentRunner}，{@link #run(String)} 直接
 * 转发给目标并原样返回其答案；交接语义由装配方（如 {@link HandoffTool} 或上层
 * 编排）据此终止父循环。本包装本身不做深度/环检测（交接是一次性转移，不形成委派栈）。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class HandoffAgent implements AgentRunner {

	private final AgentRunner target;
	private final String handoffId;
	private final String description;

	private HandoffAgent(AgentRunner target, String handoffId, String description) {
		Assert.notNull(target, "target must not be null");
		Assert.notBlank(handoffId, "handoffId must not be blank");
		this.target = target;
		this.handoffId = handoffId;
		this.description = description == null ? "" : description;
	}

	/**
	 * 包装目标 Agent 为交接节点。
	 *
	 * @param target      交接目标
	 * @param handoffId   交接节点稳定标识（展示/日志用）
	 * @param description 交接说明
	 * @return Handoff 包装
	 */
	public static HandoffAgent of(AgentRunner target, String handoffId, String description) {
		return new HandoffAgent(target, handoffId, description);
	}

	/**
	 * 交接目标。
	 *
	 * @return 目标 AgentRunner
	 */
	public AgentRunner target() {
		return this.target;
	}

	@Override
	public String id() {
		return this.handoffId;
	}

	@Override
	public String description() {
		return this.description;
	}

	@Override
	public String run(String task) throws Exception {
		return this.target.run(task);
	}
}
