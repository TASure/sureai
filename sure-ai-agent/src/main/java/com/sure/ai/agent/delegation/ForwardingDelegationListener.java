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

import com.sure.ai.agent.AgentListener;
import com.sure.ai.model.ToolCall;

/**
 * 委派事件冒泡监听器：把子 Agent 的同步回调转发给父 Agent 的监听者。
 *
 * <p>子 Agent 不配置 listener 也能正常工作（父监听者为 null 时全部回调静默丢弃）；
 * 每个转发事件都携带 {@link DelegationContext}（来源 agentId、深度、调用链），
 * 供上层按委派路径区分事件归属。转发异常被隔离，绝不反向打断子 Agent 执行。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
final class ForwardingDelegationListener implements AgentListener {

	private final AgentListener parent;
	private final DelegationContext context;

	/**
	 * 构造。
	 *
	 * @param parent  父监听者（null 表示只记录不转发）
	 * @param context 本次委派上下文（提供来源/深度/链元数据）
	 */
	ForwardingDelegationListener(AgentListener parent, DelegationContext context) {
		this.parent = parent;
		this.context = context;
	}

	/**
	 * 当前委派上下文（元数据，供测试与外层装饰读取）。
	 *
	 * @return 上下文
	 */
	DelegationContext context() {
		return this.context;
	}

	@Override
	public void onThought(String thought) {
		forward(() -> this.parent.onThought(thought));
	}

	@Override
	public void onToolCall(ToolCall toolCall) {
		forward(() -> this.parent.onToolCall(toolCall));
	}

	@Override
	public void onToolResult(ToolCall toolCall, String result) {
		forward(() -> this.parent.onToolResult(toolCall, result));
	}

	@Override
	public void onFinish(String finalAnswer) {
		forward(() -> this.parent.onFinish(finalAnswer));
	}

	@Override
	public void onError(Throwable error) {
		forward(() -> this.parent.onError(error));
	}

	@Override
	public void onPlanGenerated(List<String> steps) {
		forward(() -> this.parent.onPlanGenerated(steps));
	}

	@Override
	public void onStepStart(int index, String step) {
		forward(() -> this.parent.onStepStart(index, step));
	}

	@Override
	public void onStepComplete(int index, String result) {
		forward(() -> this.parent.onStepComplete(index, result));
	}

	/** 隔离式转发：父监听者缺失或自身抛异常都不打断子 Agent。 */
	private void forward(Runnable callback) {
		if (this.parent == null) {
			return;
		}
		try {
			callback.run();
		} catch (RuntimeException ignored) {
			// 父监听者异常隔离，不扩散到子 Agent 执行链
		}
	}
}
