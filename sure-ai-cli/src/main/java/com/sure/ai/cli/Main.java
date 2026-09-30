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
 * CLI 入口（薄壳）：装配默认依赖并把 {@link CliRunner} 的退出码交给进程。
 *
 * <p>用法示例：</p>
 * <pre>
 *   sureai chat "你好"
 *   sureai --provider deepseek stream "用一句话解释 RAG"
 *   sureai rag "公司成立于哪一年？" --doc ./company.txt
 *   sureai list
 * </pre>
 *
 * @author sureai
 * @since 2.0.0
 */
public final class Main {

	private Main() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口。
	 *
	 * @param args 命令行参数
	 */
	public static void main(String[] args) {
		CliRunner runner = new CliRunner(System.out,
			new DefaultClientFactory(Environment.SYSTEM));
		int exitCode = runner.run(args);
		System.exit(exitCode);
	}
}
