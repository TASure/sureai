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

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;
import com.sure.tool.lang.Assert;

/**
 * Handoff 工具化适配器：把交接目标 Agent 包装成工具（与 {@link AgentAsTool} 平行）。
 *
 * <p>与 AgentAsTool 的关键差别落在<b>结果标记与终止语义</b>：Handoff 工具返回的文本
 * 携带 {@value #HANDOFF_PREFIX} 前缀并附交接目标 id，明确告知上层「这是一次任务交接，
 * 目标答案即最终答案，父循环不应再继续推理」；而 AgentAsTool 把答案作为普通观察回灌。</p>
 *
 * <p>本适配器不做深度/环检测（交接一次性转移）；目标 Agent 内部异常原样向上抛出，
 * 由父编排器按既有失败隔离策略回灌。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class HandoffTool {

	/** 交接结果前缀标记。 */
	public static final String HANDOFF_PREFIX = "[HANDOFF]";

	/** 主任务参数名（与 AgentAsTool 对齐）。 */
	public static final String PARAM_TASK = "task";

	/** 工具参数 JSON Schema。 */
	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "task": {
			      "type": "string",
			      "description": "交接给目标 Agent 的完整任务描述"
			    }
			  }
			}
			""";

	private HandoffTool() {
	}

	/**
	 * 构造工具函数声明。
	 *
	 * @param targetId    目标 Agent 标识
	 * @param description 交接说明
	 * @return 工具函数
	 */
	public static ToolFunction toToolFunction(String targetId, String description) {
		Assert.notBlank(targetId, "targetId must not be blank");
		return ToolFunction.of(targetId, description == null ? "" : description, SCHEMA);
	}

	/**
	 * 构造执行处理器：运行目标并把答案包成交接结果。
	 *
	 * @param target 交接目标
	 * @return 工具处理器
	 */
	public static ToolHandler toHandler(AgentRunner target) {
		Assert.notNull(target, "target must not be null");
		return (JsonObject arguments) -> {
			String task = arguments == null ? "" : arguments.optString(PARAM_TASK, "");
			String answer = target.run(task);
			return HANDOFF_PREFIX + target.id() + " " + (answer == null ? "" : answer);
		};
	}

	/**
	 * 注册交接工具进注册中心。
	 *
	 * @param target    交接目标
	 * @param registry  工具注册中心
	 * @param description 交接说明
	 */
	public static void register(AgentRunner target, ToolRegistry registry,
			String description) {
		Assert.notNull(target, "target must not be null");
		Assert.notNull(registry, "registry must not be null");
		registry.register(toToolFunction(target.id(), description), toHandler(target));
	}
}
