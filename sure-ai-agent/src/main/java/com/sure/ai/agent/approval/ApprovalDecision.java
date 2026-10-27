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

/**
 * 一次审批的决定结果。
 *
 * @param status         审批状态
 * @param reason         审批人备注/拒绝原因（可空）
 * @param decidedAtEpochMs 决定时间戳（毫秒）
 * @author sureai
 * @since 1.7.0
 */
public record ApprovalDecision(ApprovalStatus status, String reason, long decidedAtEpochMs) {

	/**
	 * 紧凑构造器：状态非空校验。
	 *
	 * @param status         审批状态
	 * @param reason         备注
	 * @param decidedAtEpochMs 决定时间戳
	 */
	public ApprovalDecision {
		if (status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		if (decidedAtEpochMs <= 0) {
			decidedAtEpochMs = System.currentTimeMillis();
		}
	}

	/**
	 * 审批通过（无备注）。
	 *
	 * @return 决定
	 */
	public static ApprovalDecision approved() {
		return new ApprovalDecision(ApprovalStatus.APPROVED, null, System.currentTimeMillis());
	}

	/**
	 * 审批通过（带备注）。
	 *
	 * @param reason 备注
	 * @return 决定
	 */
	public static ApprovalDecision approved(String reason) {
		return new ApprovalDecision(ApprovalStatus.APPROVED, reason, System.currentTimeMillis());
	}

	/**
	 * 审批拒绝。
	 *
	 * @param reason 拒绝原因
	 * @return 决定
	 */
	public static ApprovalDecision rejected(String reason) {
		return new ApprovalDecision(ApprovalStatus.REJECTED, reason, System.currentTimeMillis());
	}

	/**
	 * 审批超时。
	 *
	 * @return 决定
	 */
	public static ApprovalDecision timeout() {
		return new ApprovalDecision(ApprovalStatus.TIMEOUT, "等待审批超时", System.currentTimeMillis());
	}
}
