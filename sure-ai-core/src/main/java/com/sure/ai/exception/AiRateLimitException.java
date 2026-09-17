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
 * 限流异常（HTTP 429）。
 *
 * <p>携带服务端要求的重试等待秒数，字段可能为 null（服务端未返回 Retry-After 头）。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class AiRateLimitException extends AiApiException {

	private static final long serialVersionUID = 1L;

	/** 服务端要求的重试等待秒数，可能为 null */
	private final Integer retryAfterSeconds;

	/**
	 * 构造限流异常，HTTP 状态码固定为 429。
	 *
	 * @param message            异常信息
	 * @param rawBody            原始响应体
	 * @param retryAfterSeconds  服务端要求的重试等待秒数，可为 null
	 */
	public AiRateLimitException(String message, String rawBody, Integer retryAfterSeconds) {
		super(429, "rate_limit", message, rawBody);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	/**
	 * 获取服务端要求的重试等待秒数。
	 *
	 * @return 重试等待秒数，可能为 null
	 */
	public Integer getRetryAfterSeconds() {
		return this.retryAfterSeconds;
	}
}
