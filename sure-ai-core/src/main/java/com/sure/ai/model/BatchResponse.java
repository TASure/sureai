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
 * 批处理任务响应。
 *
 * <p>Batches 为异步任务：提交后返回 id，需通过 {@link com.sure.ai.client.BatchClient#getBatch(String)}
 * 轮询直至进入终态。常见状态：validating / in_progress / finalizing / completed / failed / cancelled / expired。</p>
 *
 * @param id           任务 ID
 * @param status       任务状态
 * @param createdAt    创建时间戳（Unix 秒，未知为 0）
 * @param completedAt  完成时间戳（Unix 秒，未完成或未知为 0）
 * @param requestCounts 请求计数
 * @param error        失败原因（成功时为 null）
 * @param rawJson      原始响应 JSON
 * @author sureai
 * @since 0.2.0
 */
public record BatchResponse(String id, String status, long createdAt, long completedAt,
		RequestCounts requestCounts, String error, String rawJson) {

	/**
	 * 请求计数。
	 *
	 * @param total    总请求数
	 * @param completed 已完成数
	 * @param failed   失败数
	 */
	public record RequestCounts(int total, int completed, int failed) {

		/**
		 * 静态工厂。
		 *
		 * @param total    总数
		 * @param completed 已完成
		 * @param failed   失败
		 * @return 请求计数
		 */
		public static RequestCounts of(int total, int completed, int failed) {
			return new RequestCounts(total, completed, failed);
		}
	}

	/**
	 * 紧凑构造器：requestCounts 为 null 时归零。
	 *
	 * @param id           任务 ID
	 * @param status       状态
	 * @param createdAt    创建时间戳
	 * @param completedAt  完成时间戳
	 * @param requestCounts 请求计数
	 * @param error        失败原因
	 * @param rawJson      原始 JSON
	 */
	public BatchResponse {
		if (requestCounts == null) {
			requestCounts = new RequestCounts(0, 0, 0);
		}
	}

	/**
	 * 静态工厂。
	 *
	 * @param id           任务 ID
	 * @param status       状态
	 * @param createdAt    创建时间戳
	 * @param completedAt  完成时间戳
	 * @param requestCounts 请求计数
	 * @param error        失败原因
	 * @param rawJson      原始 JSON
	 * @return 批处理响应
	 */
	public static BatchResponse of(String id, String status, long createdAt, long completedAt,
			RequestCounts requestCounts, String error, String rawJson) {
		return new BatchResponse(id, status, createdAt, completedAt, requestCounts, error, rawJson);
	}

	/**
	 * 是否已到成功终态。
	 *
	 * @return completed 返回 true
	 */
	public boolean isCompleted() {
		return "completed".equals(this.status);
	}

	/**
	 * 是否已到失败终态。
	 *
	 * @return failed 返回 true
	 */
	public boolean isFailed() {
		return "failed".equals(this.status);
	}
}
