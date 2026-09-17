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
 * 鉴权失败异常（HTTP 401 / 403）。
 *
 * <p>通常由 API Key 无效、过期或权限不足引起。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class AiAuthException extends AiApiException {

	private static final long serialVersionUID = 1L;

	/**
	 * 构造鉴权失败异常，HTTP 状态码固定为 401。
	 *
	 * @param message 异常信息
	 * @param rawBody 原始响应体
	 */
	public AiAuthException(String message, String rawBody) {
		this(401, message, rawBody);
	}

	/**
	 * 构造鉴权失败异常，可指定 401 或 403。
	 *
	 * @param httpStatus HTTP 状态码（401 或 403）
	 * @param message    异常信息
	 * @param rawBody    原始响应体
	 */
	public AiAuthException(int httpStatus, String message, String rawBody) {
		super(httpStatus, "auth_error", message, rawBody);
	}
}
