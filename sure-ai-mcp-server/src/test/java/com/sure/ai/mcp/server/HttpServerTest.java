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

package com.sure.ai.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.McpClient;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * HTTP 服务端传输测试：真实本地回环（127.0.0.1），用 sure-ai-mcp 的
 * {@link StreamableHttpMcpTransport} 客户端连本地端口。
 *
 * @author sureai
 * @since 1.5.0
 */
public class HttpServerTest {

	@Test
	public void httpJsonRoundTrip() throws Exception {
		McpServer server = new McpServer().serverInfo("e2e-http", "1.0.0");
		server.registerTool(new McpServerTool("ping", "pong", Json.object(),
			args -> new McpToolResult(false, List.of("pong"))));
		HttpMcpServerTransport http = new HttpMcpServerTransport(0, "/mcp");
		server.start(http);

		try (McpClient client = McpClient.http(http.endpoint())
			.timeout(Duration.ofSeconds(10)).build()) {
			assertEquals(1, client.toolsList().size());
			assertEquals("ping", client.toolsList().get(0).name());
			McpToolResult r = client.toolsCall("ping", Json.object());
			assertFalse(r.isError());
			assertEquals("pong", r.asText());
		} finally {
			server.close();
		}
	}

	@Test
	public void statelessDiscoverWithoutInitialize() throws Exception {
		McpServer server = new McpServer().serverInfo("stateless", "2.0");
		server.registerTool(new McpServerTool("t", null, Json.object(),
			args -> new McpToolResult(false, List.of("x"))));
		HttpMcpServerTransport http = new HttpMcpServerTransport(0, "/mcp");
		server.start(http);

		try {
			// 直接 POST tools/list，带 _meta 协议版本，不经 initialize
			JsonObject meta = Json.object();
			meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
			JsonObject params = Json.object();
			params.set("_meta", meta);
			JsonObject body = Json.object();
			body.put("jsonrpc", "2.0");
			body.put("id", 1);
			body.put("method", "tools/list");
			body.set("params", params);

			HttpClient client = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(java.net.URI.create(http.endpoint()))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(200, resp.statusCode());
			JsonObject result = Json.parse(resp.body()).getAsJsonObject().getJsonObject("result");
			assertEquals("complete", result.getString("resultType"));
			assertTrue(result.has("ttlMs"));
			assertEquals("public", result.getString("cacheScope"));
			assertNotNull(resp.headers().firstValue("Mcp-Session-Id").orElse(null));
		} finally {
			server.close();
		}
	}

	@Test
	public void discoverEndpoint() throws Exception {
		McpServer server = new McpServer();
		HttpMcpServerTransport http = new HttpMcpServerTransport(0, "/mcp");
		server.start(http);
		try {
			JsonObject body = Json.object();
			body.put("jsonrpc", "2.0");
			body.put("id", "discover-1");
			body.put("method", "server/discover");
			HttpClient client = HttpClient.newHttpClient();
			HttpRequest req = HttpRequest.newBuilder(java.net.URI.create(http.endpoint()))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			JsonObject result = Json.parse(resp.body()).getAsJsonObject().getJsonObject("result");
			assertEquals("complete", result.getString("resultType"));
			assertTrue(result.getJsonArray("supportedVersions").size() >= 1);
		} finally {
			server.close();
		}
	}
}
