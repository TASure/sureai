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

package com.sure.ai.mcp;

import java.util.List;

/**
 * MCP 客户端静态入口工具类。
 *
 * <p>与各平台 Util 风格一致，提供 stdio / HTTP 两种传输的便捷工厂方法。
 * MCP 是有状态握手的长连接客户端，本工具类不做单例缓存——每次调用都返回新 Builder，
 * 由调用方负责 {@code close()}（建议 try-with-resources）。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class McpUtil {

	private McpUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * stdio 传输 Builder。
	 *
	 * @param command 可执行命令（如 {@code node}、{@code python}）
	 * @param args    命令参数
	 * @return 客户端 Builder
	 */
	public static McpClient.Builder stdio(String command, String... args) {
		McpClient.Builder b = McpClient.stdio(command);
		if (args != null) {
			b.args(List.of(args));
		}
		return b;
	}

	/**
	 * Streamable HTTP 传输 Builder。
	 *
	 * @param url 单端点 URL
	 * @return 客户端 Builder
	 */
	public static McpClient.Builder http(String url) {
		return McpClient.http(url);
	}
}
