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

import java.util.List;

import com.sure.ai.client.AiClient;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.server.McpServer;
import com.sure.ai.mcp.server.SureAiTools;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;

/**
 * MCP Server 示例（stdio 协议引擎）：注册一个离线 fake chat 工具，
 * 直接 dispatch JSON-RPC 帧演示 initialize→tools/list→tools/call，全程零网络、不阻塞。
 *
 * <p>真实部署时把最后一行换成 {@code server.start(new StdioMcpServerTransport())} 即可对外服务。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
public final class McpServerDemo {

	private McpServerDemo() {
	}

	/** 离线 fake 对话客户端，无 API key。 */
	private static final class FakeChat implements AiClient {
		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			return ChatResponse.of("fake-id", request.model(),
				List.of(Choice.of(0, ChatMessage.assistant("echo: " + request.messages().get(0).content()), "stop")),
				null, null);
		}

		@Override
		public void chatStream(ChatRequest request, java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> c) {
		}

		@Override
		public void close() {
		}
	}

	/**
	 * 入口。
	 *
	 * @param args 未使用
	 */
	public static void main(String[] args) {
		McpServer server = new McpServer().serverInfo("sureai-demo", "1.5.0")
			.registerTool(SureAiTools.chatTool(new FakeChat()));

		System.out.println("=== initialize ===");
		System.out.println(server.dispatch(req(1, "initialize", null)));

		System.out.println("=== tools/list ===");
		System.out.println(server.dispatch(req(2, "tools/list", null)));

		JsonObject callParams = Json.object();
		callParams.put("name", "sureai.chat");
		JsonObject arguments = Json.object();
		arguments.put("model", "gpt-x");
		arguments.put("prompt", "hello mcp");
		callParams.set("arguments", arguments);
		System.out.println("=== tools/call sureai.chat ===");
		System.out.println(server.dispatch(req(3, "tools/call", callParams)));

		System.out.println("（真实部署：server.start(new StdioMcpServerTransport()) 即对外提供 stdio 服务）");
	}

	private static String req(long id, String method, JsonObject params) {
		JsonObject o = Json.object();
		o.put("jsonrpc", "2.0");
		o.put("id", id);
		o.put("method", method);
		if (params != null) {
			o.set("params", params);
		}
		return Json.stringify(o);
	}
}
