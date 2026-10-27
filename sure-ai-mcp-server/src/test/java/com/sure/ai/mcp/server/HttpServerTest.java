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

import java.io.InputStream;
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

	// ==================== 2026-07-28 无状态扩展面（v2.3.0 收口） ====================

	/** RFC 9728：GET /.well-known/oauth-protected-resource[&lt;path&gt;] 返回受保护资源元数据。 */
	@Test
	public void wellKnownProtectedResourceMetadata() throws Exception {
		McpServer server = new McpServer();
		HttpMcpServerTransport http = new HttpMcpServerTransport(0, "/mcp")
			.authorizationServers("https://as.example.com")
			.scopesSupported("mcp:tools");
		server.start(http);
		try {
			HttpClient client = HttpClient.newHttpClient();
			HttpRequest hr = HttpRequest.newBuilder(
				java.net.URI.create("http://127.0.0.1:" + http.getPort()
					+ "/.well-known/oauth-protected-resource/mcp"))
				.GET().build();
			HttpResponse<String> resp = client.send(hr, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(200, resp.statusCode());
			JsonObject meta = Json.parse(resp.body()).getAsJsonObject();
			assertTrue(meta.getString("resource").endsWith("/mcp"));
			assertEquals("https://as.example.com", meta.getJsonArray("authorization_servers").get(0).getAsString());
			assertEquals("mcp:tools", meta.getJsonArray("scopes_supported").get(0).getAsString());
		} finally {
			server.close();
		}
	}

	/** subscriptions/listen：POST 升级为 SSE 流，首帧为 acknowledged 通知并带 subscriptionId。 */
	@Test
	public void subscriptionListenOpensSseStream() throws Exception {
		McpServer server = new McpServer().serverInfo("sse", "1");
		server.registerTool(new McpServerTool("t", null, Json.object(),
			args -> new McpToolResult(false, List.of("x"))));
		HttpMcpServerTransport http = new HttpMcpServerTransport(0, "/mcp");
		server.start(http);
		try {
			JsonObject filter = Json.object();
			filter.put("toolsListChanged", Boolean.TRUE);
			JsonObject params = Json.object();
			params.set("notifications", filter);
			JsonObject body = Json.object();
			body.put("jsonrpc", "2.0");
			body.put("id", 42);
			body.put("method", "subscriptions/listen");
			body.set("params", params);

			HttpClient client = HttpClient.newHttpClient();
			HttpRequest hr = HttpRequest.newBuilder(java.net.URI.create(http.endpoint()))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
				.build();
			HttpResponse<InputStream> resp = client.send(hr, HttpResponse.BodyHandlers.ofInputStream());
			assertEquals(200, resp.statusCode());
			assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
			try (InputStream in = resp.body()) {
				String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				assertTrue(s, s.contains("notifications/subscriptions/acknowledged"));
				assertTrue(s, s.contains("subscriptionId\":42"));
			}
		} finally {
			server.close();
		}
	}

	/** 标准请求头不一致：携带 Mcp-Method 但与 body 不符 → 400 + -32020 HeaderMismatch。 */
	@Test
	public void headerMismatchReturns400() throws Exception {
		McpServer server = new McpServer();
		HttpMcpServerTransport http = new HttpMcpServerTransport(0, "/mcp");
		server.start(http);
		try {
			JsonObject body = Json.object();
			body.put("jsonrpc", "2.0");
			body.put("id", 1);
			body.put("method", "tools/list");
			body.set("params", Json.object());
			HttpClient client = HttpClient.newHttpClient();
			HttpRequest hr = HttpRequest.newBuilder(java.net.URI.create(http.endpoint()))
				.header("Content-Type", "application/json")
				.header("Mcp-Method", "tools/call")
				.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> resp = client.send(hr, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(400, resp.statusCode());
			JsonObject err = Json.parse(resp.body()).getAsJsonObject().getJsonObject("error");
			assertEquals(-32020, err.getInt("code"));
		} finally {
			server.close();
		}
	}

	/** 标准请求头一致：放行。 */
	@Test
	public void matchingHeadersAccepted() throws Exception {
		McpServer server = new McpServer().serverInfo("h", "1");
		server.registerTool(new McpServerTool("echo", null, Json.object(),
			args -> new McpToolResult(false, List.of("ok"))));
		HttpMcpServerTransport http = new HttpMcpServerTransport(0, "/mcp");
		server.start(http);
		try {
			JsonObject params = Json.object();
			params.put("name", "echo");
			params.set("arguments", Json.object());
			JsonObject body = Json.object();
			body.put("jsonrpc", "2.0");
			body.put("id", 1);
			body.put("method", "tools/call");
			body.set("params", params);
			HttpClient client = HttpClient.newHttpClient();
			HttpRequest hr = HttpRequest.newBuilder(java.net.URI.create(http.endpoint()))
				.header("Content-Type", "application/json")
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", "echo")
				.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> resp = client.send(hr, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(200, resp.statusCode());
			assertTrue(Json.parse(resp.body()).getAsJsonObject().has("result"));
		} finally {
			server.close();
		}
	}
}
