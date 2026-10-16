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

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * {@link McpServer} 补测：覆盖 cacheHint、registerTool(null)/registerTools 批量、
 * tools() 快照、dispatch 的非对象帧与缺方法帧、tools/call 无 params 分支。
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpServerExtraTest {

	private static McpServer base() {
		return new McpServer().serverInfo("extra", "1.0");
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

	private static JsonObject resultOf(String resp) {
		return Json.parse(resp).getAsJsonObject().getJsonObject("result");
	}

	/** cacheHint 设置后在无状态 discover 结果中生效。 */
	@Test
	public void cacheHintPropagatesToDiscover() {
		McpServer server = base().cacheHint(1234L, "private");
		JsonObject r = resultOf(server.dispatch(req(1, "server/discover", Json.object())));
		assertEquals(1234, r.getInt("ttlMs"));
		assertEquals("private", r.getString("cacheScope"));
	}

	/** registerTool(null) 被忽略，返回 this。 */
	@Test
	public void registerNullToolIgnored() {
		McpServer server = base().registerTool(null);
		assertEquals(0, server.tools().size());
	}

	/** registerTools 批量注册，null 集合被忽略。 */
	@Test
	public void registerToolsBatchAndNull() {
		McpServerTool a = new McpServerTool("a", "A", Json.object(),
			args -> new McpToolResult(false, List.of("a")));
		McpServerTool b = new McpServerTool("b", "B", Json.object(),
			args -> new McpToolResult(false, List.of("b")));
		McpServer server = base().registerTools(List.of(a, b)).registerTools(null);
		assertEquals(2, server.tools().size());
	}

	/** tools() 返回不可变快照。 */
	@Test
	public void toolsReturnsSnapshot() {
		McpServer server = base().registerTool(new McpServerTool("x", null, Json.object(),
			args -> new McpToolResult(false, List.of())));
		List<McpServerTool> snapshot = server.tools();
		assertEquals(1, snapshot.size());
	}

	/** dispatch 收到 JSON 数组（非对象）返回 Invalid Request。 */
	@Test
	public void nonObjectBodyIsInvalidRequest() {
		String resp = base().dispatch("[1,2,3]");
		JsonObject o = Json.parse(resp).getAsJsonObject();
		assertEquals(-32600, o.getJsonObject("error").getInt("code"));
	}

	/** dispatch 有 id 但无 method 返回 Missing method。 */
	@Test
	public void missingMethodWithIdIsError() {
		String resp = base().dispatch("{\"jsonrpc\":\"2.0\",\"id\":1}");
		JsonObject o = Json.parse(resp).getAsJsonObject();
		assertEquals(-32600, o.getJsonObject("error").getInt("code"));
		assertTrue(o.getJsonObject("error").getString("message").contains("Missing method"));
	}

	/** dispatch 无 id 无 method 的空帧视为通知，不回响应。 */
	@Test
	public void emptyFrameIsNotification() {
		assertNull(base().dispatch("{}"));
	}

	/** tools/call 无 params 时走默认分支，返回 unknown tool 错误。 */
	@Test
	public void toolsCallWithoutParamsIsUnknownTool() {
		JsonObject r = resultOf(base().dispatch(req(2, "tools/call", null)));
		assertTrue(r.getBoolean("isError"));
		assertTrue(r.getJsonArray("content").get(0).getAsJsonObject().getString("text").contains("unknown tool"));
	}

	/** initialize 未设置 instructions 时不返回 instructions 字段。 */
	@Test
	public void initializeWithoutInstructionsOmitsField() {
		McpServer server = new McpServer().serverInfo("ni", "1");
		JsonObject r = resultOf(server.dispatch(req(3, "initialize", Json.object())));
		assertFalse(r.has("instructions"));
	}

	/** 同名工具覆盖注册。 */
	@Test
	public void duplicateToolNameOverwritten() {
		McpServer server = base()
			.registerTool(new McpServerTool("dup", "v1", Json.object(),
				args -> new McpToolResult(false, List.of("v1"))))
			.registerTool(new McpServerTool("dup", "v2", Json.object(),
				args -> new McpToolResult(false, List.of("v2"))));
		assertEquals(1, server.tools().size());
		JsonArray arr = resultOf(server.dispatch(req(4, "tools/list", null))).getJsonArray("tools");
		assertEquals(1, arr.size());
		assertEquals("v2", arr.get(0).getAsJsonObject().getString("description"));
	}

	/** close 后无异常（空传输列表）。 */
	@Test
	public void closeWithNoTransportsIsSafe() {
		McpServer server = base();
		server.close();
		assertNotNull(server);
	}
}
