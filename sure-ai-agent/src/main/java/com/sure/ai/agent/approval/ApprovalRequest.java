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

import java.util.UUID;

import com.sure.ai.internal.json.JsonObject;

/**
 * 一次工具调用的审批请求。
 *
 * <p>由编排器在工具执行前构造并交给 {@link ApprovalHandler} 等待人工/自动决策。
 * 不可变；{@link #arguments()} 直接复用模型解析后的参数对象快照。</p>
 *
 * @param requestId        审批请求唯一标识（UUID）
 * @param agentId          发起审批的编排器标识（可空）
 * @param toolName         待审批的工具名
 * @param arguments        工具参数（JSON 对象快照，可空表示无参数）
 * @param description      人类可读的动作描述（如“调用 payment.transfer，金额 100”）
 * @param highRisk         是否高危动作（资金/写操作等，供 handler 展示/分流）
 * @param requestedAtEpochMs 审批发起时间戳（毫秒）
 * @author sureai
 * @since 1.7.0
 */
public record ApprovalRequest(String requestId, String agentId, String toolName,
		JsonObject arguments, String description, boolean highRisk, long requestedAtEpochMs) {

	/**
	 * 紧凑构造器：requestId 缺省时自动生成 UUID。
	 *
	 * @param requestId        审批请求唯一标识
	 * @param agentId          编排器标识（可空）
	 * @param toolName         工具名
	 * @param arguments        工具参数
	 * @param description      人类可读描述
	 * @param highRisk         是否高危
	 * @param requestedAtEpochMs 发起时间戳
	 */
	public ApprovalRequest {
		if (requestId == null || requestId.isBlank()) {
			requestId = UUID.randomUUID().toString();
		}
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("toolName must not be blank");
		}
	}

	/**
	 * 静态工厂：自动生成 requestId 与当前时间戳。
	 *
	 * @param agentId     编排器标识（可空）
	 * @param toolName    工具名
	 * @param arguments   工具参数
	 * @param description 人类可读描述
	 * @param highRisk    是否高危
	 * @return 审批请求
	 */
	public static ApprovalRequest of(String agentId, String toolName, JsonObject arguments,
			String description, boolean highRisk) {
		return new ApprovalRequest(UUID.randomUUID().toString(), agentId, toolName, arguments,
			description, highRisk, System.currentTimeMillis());
	}
}
