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
 * AI 服务端返回非成功状态码时抛出的异常。
 *
 * <p>携带 HTTP 状态码、平台错误码、人类可读信息与原始响应体，便于调用方排查问题。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class AiApiException extends AiException {

	private static final long serialVersionUID = 1L;

	/** HTTP 状态码 */
	private final int httpStatus;

	/** 平台返回的业务错误码，可能为 null */
	private final String errorCode;

	/** 原始响应体文本 */
	private final String rawBody;

	/**
	 * 全字段构造器。
	 *
	 * @param httpStatus HTTP 状态码
	 * @param errorCode  平台业务错误码，可为 null
	 * @param message    异常信息
	 * @param rawBody    原始响应体
	 */
	public AiApiException(int httpStatus, String errorCode, String message, String rawBody) {
		super(message);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
		this.rawBody = rawBody;
	}

	/**
	 * 获取 HTTP 状态码。
	 *
	 * @return HTTP 状态码
	 */
	public int getHttpStatus() {
		return this.httpStatus;
	}

	/**
	 * 获取平台业务错误码。
	 *
	 * @return 错误码，可能为 null
	 */
	public String getErrorCode() {
		return this.errorCode;
	}

	/**
	 * 获取原始响应体。
	 *
	 * @return 原始响应体文本
	 */
	public String getRawBody() {
		return this.rawBody;
	}
}
