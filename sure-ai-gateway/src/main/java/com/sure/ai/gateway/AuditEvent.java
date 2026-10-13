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

package com.sure.ai.gateway;

/**
 * 审计事件：一次网关请求的概要信息。
 *
 * <p>审计日志只记录请求概要 / 路由结果 / 成本 / 租户，<b>不落全文</b>——
 * 不包含消息体，也不包含完整响应；{@code detail} 字段只放异常类名或一句话原因
 * （由 {@code GatewayAuditLogger} 截断后落日志）。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public record AuditEvent(
	String requestId,
	String method,
	String model,
	String tenantId,
	String platform,
	String instanceId,
	Status status,
	long durationMs,
	double estimatedCostUsd,
	String detail,
	long timestampMs) {

	/** 审计结果状态。 */
	public enum Status {
		/** 成功。 */
		SUCCESS,
		/** 失败。 */
		FAILED,
		/** 超额失败（触发预算 / 限额）。 */
		FAILED_OVER,
		/** 被拒绝（如熔断快速失败、无可用实例）。 */
		REJECTED,
		/** 超时。 */
		TIMEOUT
	}
}
