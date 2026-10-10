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

/**
 * {@link HttpMcpServerTransport} 补测：覆盖 OPTIONS/405、路径默认值、启动前取端口、
 * 头校验的各种放行/拒绝分支，以及 subscriptions/listen 缺 id/params 的容错。
 *
 * @author sureai
 * @since 2.6.0
 */
public class HttpMcpServerTransportExtraTest {

	private McpServer server;
	private HttpMcpServerTransport http;
	private HttpClient client;

	/** 启动本地服务。 */
	@Before
	public void setUp() throws Exception {
		this.server = new McpServer().serverInfo("httpextra", "1.0");
		this.server.registerTool(new McpServerTool("echo", "e", Json.object(),
			args -> new McpToolResult(false, List.of("ok"))));
		this.http = new HttpMcpServerTransport(0, null);
		this.server.start(this.http);
		this.client = HttpClient.newHttpClient();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.close();
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return this.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/** path 传 null 时退化为 /mcp。 */
	@Test
	public void nullPathDefaultsToMcp() {
		assertTrue(this.http.endpoint().endsWith("/mcp"));
	}

	/** getPort 在启动前返回构造端口（0）。 */
	@Test
	public void getPortBeforeStartReturnsConfigured() {
		HttpMcpServerTransport t = new HttpMcpServerTransport(0, "/x");
		assertEquals(0, t.getPort());
	}

	/** OPTIONS 预检返回 204。 */
	@Test
	public void optionsReturns204() throws Exception {
		HttpRequest r = HttpRequest.newBuilder(java.net.URI.create(this.http.endpoint()))
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
			.build();
		assertEquals(204, send(r).statusCode());
	}

	/** 非 POST/OPTIONS 请求返回 405。 */
	@Test
	public void getOnMcpEndpointReturns405() throws Exception {
		HttpRequest r = HttpRequest.newBuilder(java.net.URI.create(this.http.endpoint())).GET().build();
		assertEquals(405, send(r).statusCode());
	}

	/** well-known 路径非 GET/HEAD 返回 405。 */
	@Test
	public void wellKnownPostReturns405() throws Exception {
		String url = "http://127.0.0.1:" + this.http.getPort() + "/.well-known/oauth-protected-resource";
		HttpRequest r = HttpRequest.newBuilder(java.net.URI.create(url))
			.POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
			.build();
		assertEquals(405, send(r).statusCode());
	}

	/** 携带 Mcp-Method 头但 body 非法 JSON 时放行（不视为 mismatch）。 */
	@Test
	public void headerCheckWithInvalidBodyPassesThrough() throws Exception {
		HttpRequest r = HttpRequest.newBuilder(java.net.URI.create(this.http.endpoint()))
			.header("Content-Type", "application/json")
			.header("Mcp-Method", "tools/list")
			.POST(HttpRequest.BodyPublishers.ofString("not-json-at-all", StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> resp = send(r);
		// 非法 JSON 帧：handler 返回 parse error 或 202，但不应是 400
		assertTrue("不应 400, 实际 " + resp.statusCode(), resp.statusCode() != 400);
	}

	/** Mcp-Name 头存在但 body 无 params 时不 mismatch（expected 为 null）。 */
	@Test
	public void headerNameWithoutParamsPassesThrough() throws Exception {
		JsonObject body = Json.object();
		body.put("jsonrpc", "2.0");
		body.put("id", 1);
		body.put("method", "tools/call");
		HttpRequest r = HttpRequest.newBuilder(java.net.URI.create(this.http.endpoint()))
			.header("Content-Type", "application/json")
			.header("Mcp-Method", "tools/call")
			.header("Mcp-Name", "echo")
			.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> resp = send(r);
		assertTrue("不应 400, 实际 " + resp.statusCode(), resp.statusCode() != 400);
	}

	/** Mcp-Name 头与 body params.name 不一致 → 400 HeaderMismatch。 */
	@Test
	public void headerNameMismatchReturns400() throws Exception {
		JsonObject body = Json.object();
		body.put("jsonrpc", "2.0");
		body.put("id", 1);
		body.put("method", "tools/call");
		JsonObject params = Json.object();
		params.put("name", "realName");
		body.set("params", params);
		HttpRequest r = HttpRequest.newBuilder(java.net.URI.create(this.http.endpoint()))
			.header("Content-Type", "application/json")
			.header("Mcp-Method", "tools/call")
			.header("Mcp-Name", "wrongName")
			.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> resp = send(r);
		assertEquals(400, resp.statusCode());
		assertEquals(-32020, Json.parse(resp.body()).getAsJsonObject().getJsonObject("error").getInt("code"));
	}

	/** subscriptions/listen 无 id 无 params 也能完成 SSE 握手。 */
	@Test
	public void subscriptionListenWithoutIdAndParams() throws Exception {
		JsonObject body = Json.object();
		body.put("jsonrpc", "2.0");
		body.put("method", "subscriptions/listen");
		HttpRequest r = HttpRequest.newBuilder(java.net.URI.create(this.http.endpoint()))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
			.build();
		HttpResponse<java.io.InputStream> resp = this.client.send(r,
			HttpResponse.BodyHandlers.ofInputStream());
		assertEquals(200, resp.statusCode());
		assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
		try (java.io.InputStream in = resp.body()) {
			String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(s.contains("notifications/subscriptions/acknowledged"));
		}
	}
}
