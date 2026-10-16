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

package com.sure.ai.evals;

/**
 * 评估模块统一运行期异常。
 *
 * <p>用于数据集解析失败、执行配置非法、报告写出失败等「不应被容错吞掉」的场景。
 * 单条用例执行失败不抛本异常，而是记录到 {@code EvalOutcome} 中隔离，避免中断整批评估。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public class EvalException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * 构造异常。
	 *
	 * @param message 消息
	 */
	public EvalException(String message) {
		super(message);
	}

	/**
	 * 构造异常。
	 *
	 * @param message 消息
	 * @param cause   原因
	 */
	public EvalException(String message, Throwable cause) {
		super(message, cause);
	}
}
