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

package com.sure.ai.cli;

/**
 * CLI 运行期可预期错误：携带进程退出码，由 {@link CliRunner} 统一捕获并打印。
 *
 * <p>退出码约定：</p>
 * <ul>
 *   <li>{@code 1}：用户输入错误（参数缺失、未知平台、未配置凭证、文件不存在等）；</li>
 *   <li>{@code 2}：调用平台 API 时发生异常（网络、鉴权、限流、服务端错误等）。</li>
 * </ul>
 *
 * @author sureai
 * @since 2.0.0
 */
public class CliException extends RuntimeException {

	/** 用户输入错误退出码。 */
	public static final int EXIT_USAGE = 1;

	/** API 调用异常退出码。 */
	public static final int EXIT_API = 2;

	private final int exitCode;

	/**
	 * 构造异常。
	 *
	 * @param exitCode 退出码（见本类常量）
	 * @param message  面向用户的错误信息
	 */
	public CliException(int exitCode, String message) {
		super(message);
		this.exitCode = exitCode;
	}

	/**
	 * 构造异常并保留根因。
	 *
	 * @param exitCode 退出码
	 * @param message  面向用户的错误信息
	 * @param cause    根因
	 */
	public CliException(int exitCode, String message, Throwable cause) {
		super(message, cause);
		this.exitCode = exitCode;
	}

	/**
	 * 返回退出码。
	 *
	 * @return 退出码
	 */
	public int exitCode() {
		return this.exitCode;
	}
}
