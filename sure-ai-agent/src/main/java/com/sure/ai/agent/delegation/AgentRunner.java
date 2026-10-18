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

import com.sure.ai.agent.plan.PlanExecuteAgent;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.tool.lang.Assert;

/**
 * 可被委派的 Agent 最小抽象。
 *
 * <p>既有 {@link ReActAgent} 与 {@link PlanExecuteAgent} 都是 final 类、且没有实现
 * 公共运行接口（各自只有 {@code run(String)} 返回 String）。本接口在<b>不改动既有类
 * 签名</b>的前提下，用函数式句柄把任意 Agent 统一成「给定任务文本 → 返回最终答案」，
 * 供 {@link AgentAsTool} / {@link HandoffAgent} 委派调用。</p>
 *
 * <p>判定环与深度使用 {@link #id()}（调用方提供的稳定标识）；{@link #description()}
 * 同时作为包装工具的模型可见描述。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
@FunctionalInterface
public interface AgentRunner {

	/**
	 * 运行给定任务，返回最终答案。
	 *
	 * @param task 任务文本（非 null，可空串表示依赖 Agent 自带上下文）
	 * @return 最终答案文本
	 * @throws Exception 委托 Agent 内部异常（由委派层包装为 {@link DelegationException}）
	 */
	String run(String task) throws Exception;

	/**
	 * 稳定 Agent 标识（环检测 / 深度计数用，同一实例在链上重复即判环）。
	 *
	 * @return 非空标识
	 */
	default String id() {
		return getClass().getName();
	}

	/**
	 * 职责说明（作为包装工具的 description 暴露给父模型）。
	 *
	 * @return 描述文本，默认空串
	 */
	default String description() {
		return "";
	}

	/**
	 * 把 {@link ReActAgent} 适配为 {@link AgentRunner}。
	 *
	 * @param agent       ReAct 编排器
	 * @param id          稳定标识（环检测用，非空白）
	 * @param description 职责说明（写入工具描述）
	 * @return 适配句柄
	 */
	static AgentRunner of(ReActAgent agent, String id, String description) {
		Assert.notNull(agent, "agent must not be null");
		Assert.notBlank(id, "id must not be blank");
		String desc = description == null ? "" : description;
		return new AdaptedRunner(id, desc, agent::run);
	}

	/**
	 * 把 {@link PlanExecuteAgent} 适配为 {@link AgentRunner}。
	 *
	 * @param agent       Plan-Execute 编排器
	 * @param id          稳定标识（环检测用，非空白）
	 * @param description 职责说明（写入工具描述）
	 * @return 适配句柄
	 */
	static AgentRunner of(PlanExecuteAgent agent, String id, String description) {
		Assert.notNull(agent, "agent must not be null");
		Assert.notBlank(id, "id must not be blank");
		String desc = description == null ? "" : description;
		return new AdaptedRunner(id, desc, agent::run);
	}

	/**
	 * 直接由运行句柄构造（用于自定义 AgentRunner / 测试替身）。
	 *
	 * @param id          稳定标识
	 * @param description 职责说明
	 * @param runner      运行句柄
	 * @return 适配句柄
	 */
	static AgentRunner of(String id, String description, java.util.function.Function<String, String> runner) {
		Assert.notBlank(id, "id must not be blank");
		Assert.notNull(runner, "runner must not be null");
		String desc = description == null ? "" : description;
		return new AdaptedRunner(id, desc, runner::apply);
	}

	/**
	 * 内部适配器 record：把 id/description 与运行句柄绑定。
	 */
	record AdaptedRunner(String id, String description, java.util.function.Function<String, String> fn)
			implements AgentRunner {

		@Override
		public String run(String task) {
			return this.fn.apply(task);
		}
	}
}
