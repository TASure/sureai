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

package com.sure.ai.exception;

/**
 * 租户预算 / 配额超限异常。
 *
 * <p>由网关层预算执行器在调用前检查时抛出：当租户在当前预算周期内的累计成本、累计 token
 * 或 QPS 已达上限时触发。消息携带租户 ID 与“已用 / 上限”用量，便于排查。</p>
 *
 * <p>继承自 {@link AiException}，与其它 sureai 异常统一捕获。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public class AiBudgetExceededException extends AiException {

	private static final long serialVersionUID = 1L;

	/** 被限流的租户 ID。 */
	private final String tenantId;

	/**
	 * 构造异常。
	 *
	 * @param tenantId 租户 ID
	 * @param message  详细消息（含用量与上限）
	 */
	public AiBudgetExceededException(String tenantId, String message) {
		super(message);
		this.tenantId = tenantId;
	}

	/**
	 * 获取被限流的租户 ID。
	 *
	 * @return 租户 ID
	 */
	public String tenantId() {
		return this.tenantId;
	}
}
