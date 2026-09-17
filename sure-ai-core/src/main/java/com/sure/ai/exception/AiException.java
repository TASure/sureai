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
 * sureai 运行时异常基类。
 *
 * <p>所有 sureai 抛出的异常均继承自本类，便于调用方统一捕获。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class AiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * 构造异常。
	 *
	 * @param message 异常信息
	 */
	public AiException(String message) {
		super(message);
	}

	/**
	 * 构造异常。
	 *
	 * @param message 异常信息
	 * @param cause   原始异常
	 */
	public AiException(String message, Throwable cause) {
		super(message, cause);
	}
}
