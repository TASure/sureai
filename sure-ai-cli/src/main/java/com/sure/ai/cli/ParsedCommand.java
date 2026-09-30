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

import java.util.List;
import java.util.Map;

/**
 * 解析后的命令：子命令 + 全局选项 + 位置参数 + 子命令专属开关。
 *
 * @param command   子命令
 * @param global    全局选项
 * @param positional 位置参数（如问题文本、repl 无参）
 * @param flags     子命令专属开关（如 {@code doc}、{@code embedding-model}）
 * @author sureai
 * @since 2.0.0
 */
public record ParsedCommand(Command command, GlobalOptions global,
		List<String> positional, Map<String, String> flags) {

	/**
	 * 紧凑构造：防御性拷贝。
	 */
	public ParsedCommand {
		positional = List.copyOf(positional);
		flags = Map.copyOf(flags);
	}

	/**
	 * 取第一个位置参数（问题文本）。
	 *
	 * @return 问题文本
	 * @throws CliException 缺少位置参数
	 */
	public String requireQuestion() {
		if (positional.isEmpty() || positional.get(0).isBlank()) {
			throw new CliException(CliException.EXIT_USAGE,
				"缺少问题文本：用法 sureai " + command.token() + " \"你的问题\"");
		}
		return positional.get(0);
	}

	/**
	 * 取子命令开关值。
	 *
	 * @param key 开关名（不含前缀 --）
	 * @return 开关值，未设置返回 null
	 */
	public String flag(String key) {
		return flags.get(key);
	}
}
