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

package com.sure.ai.agent.delegation;

import com.sure.ai.exception.AiException;

/**
 * 层级委派异常基类。
 *
 * <p>委派链路（深度超限 / 环检测 / 子 Agent 失败 / 超时）抛出的全部异常均继承本类，
 * 便于调用方统一捕获。子 Agent 原始异常会作为 {@code cause} 保留，<b>不吞异常</b>。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public class DelegationException extends AiException {

	private static final long serialVersionUID = 1L;

	/**
	 * 构造异常。
	 *
	 * @param message 异常信息
	 */
	public DelegationException(String message) {
		super(message);
	}

	/**
	 * 构造异常（保留原始原因）。
	 *
	 * @param message 异常信息
	 * @param cause   原始异常
	 */
	public DelegationException(String message, Throwable cause) {
		super(message, cause);
	}
}
