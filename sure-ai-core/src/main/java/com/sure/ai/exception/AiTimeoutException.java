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
 * 请求超时异常。
 *
 * <p>由读超时、连接超时等网络层超时触发，包装底层 IOException 的超时原因。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class AiTimeoutException extends AiException {

	private static final long serialVersionUID = 1L;

	/**
	 * 构造超时异常。
	 *
	 * @param message 异常信息
	 */
	public AiTimeoutException(String message) {
		super(message);
	}

	/**
	 * 构造超时异常。
	 *
	 * @param message 异常信息
	 * @param cause   原始异常
	 */
	public AiTimeoutException(String message, Throwable cause) {
		super(message, cause);
	}
}
