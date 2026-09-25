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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试用最小 echo MCP server：NDJSON stdio 回环。
 *
 * <p>仅用 JDK，通过 {@code java EchoMcpServer.java} 单文件源码模式启动。
 * 支持 initialize / tools/list / tools/call；通知直接忽略。</p>
 */
public final class EchoMcpServer {

	private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(-?\\d+)");
	private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");

	/**
	 * 入口。
	 *
	 * @param args 命令行参数（未使用）
	 * @throws Exception IO 异常
	 */
	public static void main(String[] args) throws Exception {
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = in.readLine()) != null) {
			if (line.isBlank()) {
				continue;
			}
			Matcher im = ID.matcher(line);
			Matcher mm = METHOD.matcher(line);
			boolean hasId = im.find();
			String id = hasId ? im.group(1) : null;
			String method = mm.find() ? mm.group(1) : null;
			if (!hasId) {
				// 通知，忽略
				continue;
			}
			String resp;
			if ("initialize".equals(method)) {
				resp = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-06-18\","
					+ "\"capabilities\":{\"tools\":{}},"
					+ "\"serverInfo\":{\"name\":\"echo-mcp\",\"version\":\"1.0\"}}}";
			} else if ("tools/list".equals(method)) {
				resp = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{"
					+ "\"name\":\"echo\",\"description\":\"把输入原样返回\","
					+ "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}]}}";
			} else if ("tools/call".equals(method)) {
				Matcher tm = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"").matcher(line);
				String text = tm.find() ? tm.group(1) : "";
				resp = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{"
					+ "\"type\":\"text\",\"text\":\"echo:" + text + "\"}],\"isError\":false}}";
			} else {
				resp = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"unknown method\"}}";
			}
			System.out.println(resp);
			System.out.flush();
		}
	}

	private EchoMcpServer() {
	}
}
