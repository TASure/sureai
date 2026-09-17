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
 * 单条视频生成结果。
 *
 * @param url            视频访问 URL（可能为 null）
 * @param coverImageUrl  封面图 URL（可能为 null）
 * @param b64Json        Base64 编码的视频数据（可能为 null，多数平台返回 URL）
 * @param taskStatus     任务状态（异步平台透传，如 SUCCEEDED/succeeded）
 * @param revisedPrompt  平台修订后的提示词（可能为 null）
 * @author sureai
 * @since 0.2.0
 */
public record VideoResult(String url, String coverImageUrl, String b64Json,
		String taskStatus, String revisedPrompt) {

	/**
	 * 静态工厂。
	 *
	 * @param url            视频 URL
	 * @param coverImageUrl  封面图 URL
	 * @param b64Json        Base64 数据
	 * @param taskStatus     任务状态
	 * @param revisedPrompt  修订后提示词
	 * @return 结果
	 */
	public static VideoResult of(String url, String coverImageUrl, String b64Json,
			String taskStatus, String revisedPrompt) {
		return new VideoResult(url, coverImageUrl, b64Json, taskStatus, revisedPrompt);
	}

	/**
	 * 仅含 URL 的工厂。
	 *
	 * @param url 视频 URL
	 * @return 结果
	 */
	public static VideoResult ofUrl(String url) {
		return new VideoResult(url, null, null, null, null);
	}

	/**
	 * 含 URL 与封面图的工厂。
	 *
	 * @param url           视频 URL
	 * @param coverImageUrl 封面图 URL
	 * @return 结果
	 */
	public static VideoResult ofUrl(String url, String coverImageUrl) {
		return new VideoResult(url, coverImageUrl, null, null, null);
	}
}
