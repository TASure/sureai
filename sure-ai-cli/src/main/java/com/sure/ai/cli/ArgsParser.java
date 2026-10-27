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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手写命令行参数解析器（零第三方依赖，不引 picocli/jline）。
 *
 * <p>语法约定：</p>
 * <pre>
 *   sureai [全局选项] &lt;子命令&gt; [子命令选项] [位置参数...]
 *
 *   全局选项（出现在子命令之前）：
 *     --provider &lt;平台&gt;      默认 openai
 *     --api-key &lt;key&gt;        缺省回退环境变量 SURE_AI_&lt;平台&gt;_API_KEY
 *     --model &lt;模型&gt;         缺省用平台默认模型
 *     --base-url &lt;url&gt;       覆盖网关地址
 *
 *   子命令：chat | stream | rag | list | repl
 *   rag 专属：--doc &lt;本地文本文件&gt;  --embedding-model &lt;嵌入模型&gt;
 *   chat 专属：--once（单次问答，不进入多轮）
 *   new 专属：--list | --name &lt;目录名&gt; --group &lt;groupId&gt; --artifact &lt;artifactId&gt;
 *   config 专属：--export（导出 env 文件）--check（校验必填变量）
 * </pre>
 *
 * @author sureai
 * @since 2.0.0
 */
public final class ArgsParser {

	/** 各子命令允许的专属选项（v3.0.0 起按命令白名单校验）。 */
	private static final Map<Command, java.util.Set<String>> COMMAND_FLAGS = Map.of(
		Command.RAG, java.util.Set.of("doc", "embedding-model"),
		Command.CHAT, java.util.Set.of("once"),
		Command.NEW, java.util.Set.of("list", "name", "group", "artifact"),
		Command.CONFIG, java.util.Set.of("export", "check"));

	/** 无值开关（出现即 true）。 */
	private static final java.util.Set<String> NO_VALUE_FLAGS =
		java.util.Set.of("list", "export", "check", "once");

	private ArgsParser() {
		throw new AssertionError("No instances");
	}

	/**
	 * 解析命令行参数。
	 *
	 * @param args 原始参数
	 * @return 解析结果
	 * @throws CliException 用法错误（退出码 1）
	 */
	public static ParsedCommand parse(String[] args) {
		if (args == null) {
			args = new String[0];
		}
		int i = 0;
		String provider = null;
		String apiKey = null;
		String model = null;
		String baseUrl = null;

		// 1) 子命令之前的全局选项
		while (i < args.length && args[i].startsWith("--")) {
			String name = args[i].substring(2);
			String value = requireValue(args, i, name);
			switch (name) {
				case "provider" -> provider = value;
				case "api-key" -> apiKey = value;
				case "model" -> model = value;
				case "base-url" -> baseUrl = value;
				default -> throw new CliException(CliException.EXIT_USAGE, "未知全局选项: --" + name);
			}
			i += 2;
		}

		// 2) 子命令
		if (i >= args.length) {
			throw new CliException(CliException.EXIT_USAGE, "缺少子命令：chat | stream | rag | list | repl");
		}
		String cmdToken = args[i++];
		Command command = Command.parse(cmdToken);
		if (command == null) {
			throw new CliException(CliException.EXIT_USAGE, "未知子命令: " + cmdToken);
		}

		// 3) 子命令之后的选项与位置参数
		List<String> positional = new ArrayList<>();
		Map<String, String> flags = new LinkedHashMap<>();
		while (i < args.length) {
			String arg = args[i];
			if (arg.startsWith("--")) {
				String name = arg.substring(2);
				java.util.Set<String> allowed = COMMAND_FLAGS.getOrDefault(command,
					java.util.Set.of());
				if (!allowed.contains(name)) {
					throw new CliException(CliException.EXIT_USAGE, "未知选项: --" + name);
				}
				if (NO_VALUE_FLAGS.contains(name)) {
					flags.put(name, "true");
					i++;
				} else {
					flags.put(name, requireValue(args, i, name));
					i += 2;
				}
			} else {
				positional.add(arg);
				i++;
			}
		}

		return new ParsedCommand(command,
			new GlobalOptions(provider, apiKey, model, baseUrl), positional, flags);
	}

	/** 取 {@code --name} 紧随的参数值，缺失则报用法错误。 */
	private static String requireValue(String[] args, int index, String name) {
		if (index + 1 >= args.length) {
			throw new CliException(CliException.EXIT_USAGE, "选项 --" + name + " 缺少参数值");
		}
		return args[index + 1];
	}
}
