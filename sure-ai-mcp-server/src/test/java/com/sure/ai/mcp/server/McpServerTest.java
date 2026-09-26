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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * {@link McpServer} 协议引擎单元测试：直接 dispatch JSON 帧，断言响应结构。
 *
 * @author sureai
 * @since 1.5.0
 */
public class McpServerTest {

	private McpServer server;

	/** 注册一个 echo 工具。 */
	@Before
	public void setUp() {
		this.server = new McpServer()
			.serverInfo("test-server", "9.9.9")
			.instructions("be helpful");
		this.server.registerTool(new McpServerTool("echo", "echo back",
			Json.object(),
			args -> new McpToolResult(false, List.of("got:" + args.optString("text", "")))));
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

	private static JsonObject resultOf(String response) {
		return Json.parse(response).getAsJsonObject().getJsonObject("result");
	}

	@Test
	public void initializeHandshake() {
		String resp = this.server.dispatch(req(1, "initialize", Json.object()));
		JsonObject r = resultOf(resp);
		assertEquals("2025-06-18", r.getString("protocolVersion"));
		assertTrue(r.getJsonObject("capabilities").has("tools"));
		assertEquals("test-server", r.getJsonObject("serverInfo").getString("name"));
		assertEquals("be helpful", r.getString("instructions"));
	}

	@Test
	public void initializedNotificationGetsNoResponse() {
		JsonObject n = Json.object();
		n.put("jsonrpc", "2.0");
		n.put("method", "notifications/initialized");
		assertNull(this.server.dispatch(Json.stringify(n)));
	}

	@Test
	public void toolsListReturnsRegisteredTools() {
		this.server.registerTool(new McpServerTool("second", "second desc", Json.object(),
			args -> new McpToolResult(false, List.of("x"))));
		String resp = this.server.dispatch(req(2, "tools/list", null));
		JsonArray tools = resultOf(resp).getJsonArray("tools");
		assertEquals(2, tools.size());
		JsonObject first = tools.get(0).getAsJsonObject();
		assertEquals("echo", first.getString("name"));
		assertEquals("echo back", first.getString("description"));
		assertTrue(first.has("inputSchema"));
	}

	@Test
	public void toolsCallExistingTool() {
		JsonObject params = Json.object();
		params.put("name", "echo");
		JsonObject arguments = Json.object();
		arguments.put("text", "hi");
		params.set("arguments", arguments);
		String resp = this.server.dispatch(req(3, "tools/call", params));
		JsonObject r = resultOf(resp);
		assertFalse(r.getBoolean("isError"));
		JsonArray content = r.getJsonArray("content");
		assertEquals("got:hi", content.get(0).getAsJsonObject().getString("text"));
	}

	@Test
	public void toolsCallUnknownToolIsError() {
		JsonObject params = Json.object();
		params.put("name", "nope");
		params.set("arguments", Json.object());
		String resp = this.server.dispatch(req(4, "tools/call", params));
		JsonObject r = resultOf(resp);
		assertTrue(r.getBoolean("isError"));
		assertTrue(r.getJsonArray("content").get(0).getAsJsonObject().getString("text").contains("unknown tool"));
	}

	@Test
	public void toolsCallHandlerExceptionIsError() {
		this.server.registerTool(new McpServerTool("boom", null, Json.object(),
			args -> {
				throw new IllegalStateException("kaboom");
			}));
		JsonObject params = Json.object();
		params.put("name", "boom");
		params.set("arguments", Json.object());
		String resp = this.server.dispatch(req(5, "tools/call", params));
		JsonObject r = resultOf(resp);
		assertTrue(r.getBoolean("isError"));
	}

	@Test
	public void unknownMethodReturnsJsonRpcError() {
		String resp = this.server.dispatch(req(6, "nope/x", null));
		JsonObject o = Json.parse(resp).getAsJsonObject();
		assertTrue(o.has("error"));
		assertEquals(-32601, o.getJsonObject("error").getInt("code"));
	}

	@Test
	public void malformedJsonReturnsParseError() {
		String resp = this.server.dispatch("{not json");
		JsonObject o = Json.parse(resp).getAsJsonObject();
		assertTrue(o.has("error"));
		assertEquals(-32700, o.getJsonObject("error").getInt("code"));
	}

	@Test
	public void serverDiscoverStateless() {
		String resp = this.server.dispatch(req(7, "server/discover", Json.object()));
		JsonObject r = resultOf(resp);
		assertEquals("complete", r.getString("resultType"));
		JsonArray versions = r.getJsonArray("supportedVersions");
		assertTrue(versions.size() >= 2);
		assertTrue(r.has("ttlMs"));
		assertEquals("public", r.getString("cacheScope"));
		JsonObject meta = r.getJsonObject("_meta");
		assertEquals("test-server", meta.getJsonObject("io.modelcontextprotocol/serverInfo").getString("name"));
	}

	@Test
	public void toolsListStatelessCarriesCacheHints() {
		JsonObject meta = Json.object();
		meta.put(McpServer.META_PROTOCOL_VERSION, "2026-07-28");
		JsonObject params = Json.object();
		params.set("_meta", meta);
		String resp = this.server.dispatch(req(8, "tools/list", params));
		JsonObject r = resultOf(resp);
		assertEquals("complete", r.getString("resultType"));
		assertTrue(r.has("ttlMs"));
		assertEquals("public", r.getString("cacheScope"));
	}

	@Test
	public void listEndpointsEmptyByDefault() {
		assertTrue(resultOf(this.server.dispatch(req(9, "resources/list", null))).getJsonArray("resources").size() == 0);
		assertTrue(resultOf(this.server.dispatch(req(10, "prompts/list", null))).getJsonArray("prompts").size() == 0);
	}

	@Test
	public void pingReturnsEmptyResult() {
		JsonObject r = resultOf(this.server.dispatch(req(11, "ping", null)));
		assertNotNull(r);
	}
}
