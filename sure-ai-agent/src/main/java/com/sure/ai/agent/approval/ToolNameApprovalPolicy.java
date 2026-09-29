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

package com.sure.ai.agent.approval;

import java.util.Set;

import com.sure.ai.internal.json.JsonObject;
import com.sure.tool.lang.Assert;

/**
 * 仅对指定工具名集合中的工具要求审批。
 *
 * <p>匹配为精确匹配（大小写敏感）；集合在构造时快照为不可修改副本。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class ToolNameApprovalPolicy implements ApprovalPolicy {

	/** 需要审批的工具名集合。 */
	private final Set<String> toolNames;

	/**
	 * 构造策略。
	 *
	 * @param toolNames 需要审批的工具名集合（null 视为空集）
	 */
	public ToolNameApprovalPolicy(Set<String> toolNames) {
		this.toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
	}

	/**
	 * 工厂方法。
	 *
	 * @param toolNames 需要审批的工具名
	 * @return 策略
	 */
	public static ToolNameApprovalPolicy of(String... toolNames) {
		Assert.notNull(toolNames, "toolNames must not be null");
		return new ToolNameApprovalPolicy(Set.of(toolNames));
	}

	@Override
	public boolean requiresApproval(String toolName, JsonObject args) {
		return toolName != null && this.toolNames.contains(toolName);
	}
}
