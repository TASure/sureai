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
import static org.junit.Assert.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.mcp.transport.McpHeaders;

/**
 * 2.9.0 HTTP 协议对齐测试：MCP-Protocol-Version 头一致性/版本支持（-32020/-32022）、
 * 未知方法 HTTP 404。
 *
 * @author sureai
 * @since 2.9.0
 */
public class HttpMcpServerProtocolTest {

	private McpServer server;
	private HttpMcpServerTransport http;
	private HttpClient client;

	/** 启动本地服务。 */
	@Before
	public void setUp() throws Exception {
		this.server = new McpServer().serverInfo("proto", "1.0");
		this.server.registerTool(new McpServerTool("echo", "e", Json.object(),
			args -> new McpToolResult(false, List.of("ok"))));
		this.http = new HttpMcpServerTransport(0, "/mcp");
		this.server.start(this.http);
		this.client = HttpClient.newHttpClient();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.close();
	}

	private static JsonObject frame(String method, JsonObject params, boolean withId) {
		JsonObject body = Json.object();
		body.put("jsonrpc", "2.0");
		if (withId) {
			body.put("id", 1);
		}
		body.put("method", method);
		if (params != null) {
			body.set("params", params);
		}
		return body;
	}

	private HttpResponse<String> post(JsonObject body, String... headers) throws Exception {

		HttpRequest.Builder b = HttpRequest.newBuilder(java.net.URI.create(this.http.endpoint()))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
		for (int i = 0; i + 1 < headers.length; i += 2) {
			b.header(headers[i], headers[i + 1]);
		}
		return this.client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/** MCP-Protocol-Version 与 _meta 不一致 → 400 / -32020。 */
	@Test
	public void protocolVersionMismatchReturns400() throws Exception {
		JsonObject params = Json.object();
		JsonObject meta = Json.object();
		meta.put(McpServer.META_PROTOCOL_VERSION, McpServer.PROTOCOL_VERSION_STATELESS);
		params.set("_meta", meta);
		JsonObject body = frame("tools/list", params, true);
		HttpResponse<String> resp = post(body, McpHeaders.HEADER_PROTOCOL_VERSION,
			McpServer.PROTOCOL_VERSION_STATEFUL);
		assertEquals(400, resp.statusCode());
		assertEquals(-32020, Json.parse(resp.body()).getAsJsonObject().getJsonObject("error").getInt("code"));
	}

	/** MCP-Protocol-Version 为不支持版本 → 400 / -32022，data 含 supported/requested。 */
	@Test
	public void unsupportedProtocolVersionReturns400() throws Exception {
		JsonObject body = frame("tools/list", null, true);
		HttpResponse<String> resp = post(body, McpHeaders.HEADER_PROTOCOL_VERSION, "1999-01-01");
		assertEquals(400, resp.statusCode());
		JsonObject error = Json.parse(resp.body()).getAsJsonObject().getJsonObject("error");
		assertEquals(-32022, error.getInt("code"));
		assertEquals("1999-01-01", error.getJsonObject("data").getString("requested"));
		assertEquals(2, error.getJsonObject("data").getJsonArray("supported").size());
	}

	/** MCP-Protocol-Version 与 _meta 一致且受支持 → 正常 200。 */
	@Test
	public void matchingProtocolVersionPasses() throws Exception {
		JsonObject params = Json.object();
		JsonObject meta = Json.object();
		meta.put(McpServer.META_PROTOCOL_VERSION, McpServer.PROTOCOL_VERSION_STATELESS);
		params.set("_meta", meta);
		JsonObject body = frame("tools/list", params, true);
		HttpResponse<String> resp = post(body, McpHeaders.HEADER_PROTOCOL_VERSION,
			McpServer.PROTOCOL_VERSION_STATELESS);
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("tools"));
	}

	/** 未知方法 → HTTP 404 + JSON-RPC -32601。 */
	@Test
	public void unknownMethodReturns404() throws Exception {
		JsonObject body = frame("not/a/method", null, true);
		HttpResponse<String> resp = post(body);
		assertEquals(404, resp.statusCode());
		assertEquals(-32601, Json.parse(resp.body()).getAsJsonObject().getJsonObject("error").getInt("code"));
	}

	/** 已知方法正常返回 200。 */
	@Test
	public void knownMethodReturns200() throws Exception {
		JsonObject body = frame("tools/list", null, true);
		HttpResponse<String> resp = post(body);
		assertEquals(200, resp.statusCode());
	}
}
