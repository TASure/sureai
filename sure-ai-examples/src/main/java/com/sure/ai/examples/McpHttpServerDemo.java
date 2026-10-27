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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sure.ai.client.AiClient;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.server.HttpMcpServerTransport;
import com.sure.ai.mcp.server.McpServer;
import com.sure.ai.mcp.server.SureAiTools;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;

/**
 * MCP Server 示例（Streamable HTTP）：在随机端口启动本地 server，用 JDK HttpClient
 * 走 loopback 完成 initialize→tools/list→tools/call，零外部网络。
 *
 * @author sureai
 * @since 1.5.0
 */
public final class McpHttpServerDemo {

	private McpHttpServerDemo() {
	}

	private static final class FakeChat implements AiClient {
		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			return ChatResponse.of("fake-id", request.model(),
				List.of(Choice.of(0, ChatMessage.assistant("http-echo: " + request.messages().get(0).content()), "stop")),
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
	 * @throws Exception 网络异常
	 */
	public static void main(String[] args) throws Exception {
		McpServer server = new McpServer().serverInfo("sureai-http-demo", "1.5.0")
			.registerTool(SureAiTools.chatTool(new FakeChat()))
			.registerTool(SureAiTools.embedTool(text -> com.sure.ai.model.EmbeddingResponse
				.of(text.model(), List.of(new float[] { 0.1f, 0.2f }), null)));

		HttpMcpServerTransport http = new HttpMcpServerTransport(0, "/mcp");
		server.start(http);
		System.out.println("server on " + http.endpoint());

		HttpClient client = HttpClient.newHttpClient();
		System.out.println("initialize -> " + post(client, http.endpoint(), req(1, "initialize", null)));
		System.out.println("tools/list -> " + post(client, http.endpoint(), req(2, "tools/list", null)));

		JsonObject callParams = Json.object();
		callParams.put("name", "sureai.chat");
		JsonObject arguments = Json.object();
		arguments.put("model", "gpt-x");
		arguments.put("prompt", "hi http");
		callParams.set("arguments", arguments);
		System.out.println("tools/call -> " + post(client, http.endpoint(), req(3, "tools/call", callParams)));

		server.close();
	}

	private static String post(HttpClient client, String url, String body) throws Exception {
		HttpRequest r = HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> resp = client.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return "HTTP " + resp.statusCode() + " " + resp.body();
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
