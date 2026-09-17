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

package com.sure.ai.agent.plan;

import java.time.Duration;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatRequest;

/**
 * Plan-and-Execute 编排器（骨架 / skeleton）。
 *
 * <p><b>当前状态：未实现。</b>设计思路（留待后续迭代落地）：</p>
 * <ol>
 *   <li><b>Planning</b>：通过 {@code ChatRequest.responseFormat("json_object")} 让模型把任务
 *       拆解为结构化的步骤列表（JSON 数组，每项含 action / 目标）；</li>
 *   <li><b>Execution</b>：逐步用 ReAct 风格执行每个步骤（工具调用 → 观测 → 必要时修正）；</li>
 *   <li><b>汇总</b>：把各步骤结果交给模型，产出最终综合答案。</li>
 * </ol>
 *
 * <p>相比 ReAct 的「边想边做」，Plan-and-Execute 先全局规划再分步执行，适合任务分解明确、
 * 步骤间依赖较弱的场景。完整实现需要结构化输出解析、步骤间上下文管理与失败回退，
 * 为控制迭代风险，本版本仅保留构造器签名与 JavaDoc，{@link #run(String)} 统一抛
 * {@link UnsupportedOperationException}，请改用 {@link com.sure.ai.agent.react.ReActAgent}。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public final class PlanExecuteAgent {

	private final AiClient client;
	private final ChatRequest baseRequest;
	private final ToolRegistry registry;
	private final AgentListener listener;
	private final int maxIterations;
	private final Duration timeout;

	/**
	 * 构造器（签名与 ReActAgent 对齐，便于后续无缝替换）。
	 *
	 * @param client      对话客户端
	 * @param baseRequest 基础请求模板
	 * @param registry    工具注册中心
	 */
	public PlanExecuteAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry) {
		this(client, baseRequest, registry, null, 10, Duration.ofSeconds(120));
	}

	/**
	 * 全参构造器。
	 *
	 * @param client        对话客户端
	 * @param baseRequest   基础请求模板
	 * @param registry      工具注册中心
	 * @param listener      事件回调
	 * @param maxIterations 最大迭代
	 * @param timeout       总超时
	 */
	public PlanExecuteAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry,
			AgentListener listener, int maxIterations, Duration timeout) {
		this.client = client;
		this.baseRequest = baseRequest;
		this.registry = registry;
		this.listener = listener;
		this.maxIterations = maxIterations;
		this.timeout = timeout;
	}

	/**
	 * 运行（未实现）。
	 *
	 * @param userMessage 用户消息
	 * @return 永远不返回
	 */
	public String run(String userMessage) {
		throw new UnsupportedOperationException(
				"Plan-and-Execute not yet implemented; use ReActAgent. See docs/agent.md.");
	}
}
