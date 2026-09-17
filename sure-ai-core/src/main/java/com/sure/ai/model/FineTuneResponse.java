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

package com.sure.ai.model;

/**
 * 微调任务响应。
 *
 * @param id            任务 ID
 * @param status        任务状态（queued/running/succeeded/failed 等）
 * @param model         基础模型名
 * @param fineTunedModel 微调产出模型名，未完成时为 null
 * @param createdAt     创建时间戳（毫秒），未知为 null
 * @param completedAt   完成时间戳（毫秒），未完成时为 null
 * @param error         失败原因，成功时为 null
 * @param rawJson       原始 JSON
 * @author sureai
 * @since 0.2.0
 */
public record FineTuneResponse(String id, String status, String model,
		String fineTunedModel, Long createdAt, Long completedAt, String error, String rawJson) {

	/**
	 * 静态工厂。
	 *
	 * @param id            任务 ID
	 * @param status        状态
	 * @param model         基础模型
	 * @param fineTunedModel 微调模型
	 * @param createdAt     创建时间戳
	 * @param completedAt   完成时间戳
	 * @param error         失败原因
	 * @param rawJson       原始 JSON
	 * @return 响应
	 */
	public static FineTuneResponse of(String id, String status, String model,
			String fineTunedModel, Long createdAt, Long completedAt, String error, String rawJson) {
		return new FineTuneResponse(id, status, model, fineTunedModel, createdAt,
			completedAt, error, rawJson);
	}

	/**
	 * 是否已成功完成。
	 *
	 * @return 状态为 succeeded/completed 返回 true
	 */
	public boolean isCompleted() {
		return "succeeded".equals(this.status) || "completed".equals(this.status);
	}

	/**
	 * 是否失败。
	 *
	 * @return 状态为 failed/cancelled 返回 true
	 */
	public boolean isFailed() {
		return "failed".equals(this.status) || "cancelled".equals(this.status);
	}
}
