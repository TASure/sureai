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
import com.sure.tool.lang.Assert;

/**
 * 审批门面：组合 {@link ApprovalPolicy} 与 {@link ApprovalHandler}。
 *
 * <p>两个职责：</p>
 * <ul>
 *   <li>{@link #requiresApproval(String, JsonObject)}：由策略判断是否需要审批；</li>
 *   <li>{@link #request(ApprovalRequest)}：真正发起审批；若策略判定不需要，
 *       则<b>短路</b>直接返回 {@link ApprovalStatus#APPROVED}，绝不触发 handler。</li>
 * </ul>
 *
 * <p>编排器通常先调用 {@code requiresApproval} 决定是否构造请求，再调用
 * {@code request}；即使跳过第一步直接调用 {@code request}，短路语义依然生效。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class ApprovalGate {

	/** 审批策略。 */
	private final ApprovalPolicy policy;

	/** 审批处理器。 */
	private final ApprovalHandler handler;

	/**
	 * 构造。
	 *
	 * @param policy  审批策略（null 视为 {@link NeverApprovalPolicy}）
	 * @param handler 审批处理器（null 视为 {@link AutoApprovalHandler}）
	 */
	public ApprovalGate(ApprovalPolicy policy, ApprovalHandler handler) {
		this.policy = policy == null ? NeverApprovalPolicy.instance() : policy;
		this.handler = handler == null ? AutoApprovalHandler.instance() : handler;
	}

	/**
	 * 判断给定工具调用是否需要审批（委托给策略）。
	 *
	 * @param toolName 工具名
	 * @param args     工具参数
	 * @return true 表示需要审批
	 */
	public boolean requiresApproval(String toolName, JsonObject args) {
		return this.policy.requiresApproval(toolName, args);
	}

	/**
	 * 发起审批请求。
	 *
	 * <p>若策略判定不需要审批，直接返回 {@link ApprovalStatus#APPROVED}，
	 * 不触发 handler。</p>
	 *
	 * @param request 审批请求（非 null）
	 * @return 审批决定
	 */
	public ApprovalDecision request(ApprovalRequest request) {
		Assert.notNull(request, "request must not be null");
		if (!this.policy.requiresApproval(request.toolName(), request.arguments())) {
			return ApprovalDecision.approved("policy short-circuit");
		}
		return this.handler.request(request);
	}

	/**
	 * 当前策略名称（调试用）。
	 *
	 * @return 策略实现类名
	 */
	public String policyName() {
		return this.policy.getClass().getSimpleName();
	}

	/**
	 * 当前处理器名称。
	 *
	 * @return 处理器名
	 */
	public String handlerName() {
		return this.handler.name();
	}
}
