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
package com.sure.ai.examples;

import java.io.File;
import java.time.Duration;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.McpClient;
import com.sure.ai.mcp.McpUtil;

/**
 * MCP 客户端示例：用 JDK 单文件源码模式启动内置 echo MCP server，走 stdio NDJSON 握手回环。
 *
 * <p>无真实第三方 MCP server 依赖：echo server 源码随 sure-ai-mcp 测试资源提供。
 * 若在工程外运行、找不到该源码文件，则打印提示并优雅跳过，不抛异常。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class McpDemo {

	private McpDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		File server = locateEchoServer();
		if (server == null) {
			System.out.println("[McpDemo] 未找到 echo MCP server 源码（sure-ai-mcp/src/test/resources/"
				+ "echo-mcp-server/EchoMcpServer.java），跳过演示。");
			System.out.println("[McpDemo] 生产用法：McpUtil.stdio(\"node\",\"server.js\").build() 或 "
				+ "McpUtil.http(\"http://host:port/mcp\").build()");
			return;
		}
		String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
		try (McpClient client = McpUtil.stdio(javaBin, server.getAbsolutePath())
			.timeout(Duration.ofSeconds(15))
			.build()) {
			System.out.println("[McpDemo] tools: " + client.toolsList());
			JsonObject arguments = Json.object();
			arguments.set("text", "hello from sureai");
			System.out.println("[McpDemo] tools/call echo => " + client.toolsCall("echo", arguments).asText());
		} catch (RuntimeException ex) {
			System.out.println("[McpDemo] 启动 MCP server 失败，跳过：" + ex.getMessage());
		}
	}

	/** 在工程常见位置查找 echo server 源码。 */
	private static File locateEchoServer() {
		String[] candidates = {
			"sure-ai-mcp/src/test/resources/echo-mcp-server/EchoMcpServer.java",
			"../sure-ai-mcp/src/test/resources/echo-mcp-server/EchoMcpServer.java",
			"sureai/sure-ai-mcp/src/test/resources/echo-mcp-server/EchoMcpServer.java"
		};
		for (String c : candidates) {
			File f = new File(c);
			if (f.isFile()) {
				return f;
			}
		}
		return null;
	}
}
