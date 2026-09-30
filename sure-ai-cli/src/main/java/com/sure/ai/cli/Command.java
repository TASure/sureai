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

import java.util.Map;

/**
 * CLI 子命令。
 *
 * @author sureai
 * @since 2.0.0
 */
public enum Command {

	/** 一次性同步问答（非流式）。 */
	CHAT("chat"),

	/** 流式问答，逐片打印增量文本。 */
	STREAM("stream"),

	/** 基于本地文档的检索增强问答。 */
	RAG("rag"),

	/** 列出全部可用平台与默认模型。 */
	LIST("list"),

	/** 交互式多轮会话。 */
	REPL("repl");

	private static final Map<String, Command> BY_NAME = Map.of(
		"chat", CHAT,
		"stream", STREAM,
		"rag", RAG,
		"list", LIST,
		"repl", REPL
	);

	private final String token;

	Command(String token) {
		this.token = token;
	}

	/**
	 * 子命令命令行字面量。
	 *
	 * @return 字面量
	 */
	public String token() {
		return this.token;
	}

	/**
	 * 按字面量解析子命令，大小写不敏感。
	 *
	 * @param token 命令行字面量
	 * @return 子命令，未知返回 null
	 */
	public static Command parse(String token) {
		if (token == null) {
			return null;
		}
		return BY_NAME.get(token.toLowerCase());
	}
}
