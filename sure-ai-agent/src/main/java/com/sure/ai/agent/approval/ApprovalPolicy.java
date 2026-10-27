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

import com.sure.ai.internal.json.JsonObject;

/**
 * 审批策略：判断一次工具调用是否需要人工审批。
 *
 * <p>策略只回答“要不要审批”，不决定“批不批”——后者由 {@link ApprovalHandler} 完成。
 * 可按工具名、参数、风险等级等自由组合。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
@FunctionalInterface
public interface ApprovalPolicy {

	/**
	 * 判断给定工具调用是否需要审批。
	 *
	 * @param toolName 工具名
	 * @param args     已解析的工具参数（可能为空对象，但不为 null）
	 * @return true 表示执行前必须审批
	 */
	boolean requiresApproval(String toolName, JsonObject args);
}
