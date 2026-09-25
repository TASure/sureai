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
package com.sure.ai.mcp.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;

/**
 * MCP 消息层序列化/反序列化测试。
 *
 * @author sureai
 * @since 1.2.0
 */
public class McpMessageTest {

	/** 请求序列化包含 jsonrpc/id/method/params。 */
	@Test
	public void requestSerializes() {
		JsonObject params = Json.object();
		params.put("a", 1);
		McpRequest req = new McpRequest(1L, "tools/list", params);
		JsonObject o = req.toJson();
		assertEquals("2.0", o.getString("jsonrpc"));
		assertEquals(1L, o.optLong("id", 0L));
		assertEquals("tools/list", o.getString("method"));
		assertEquals(1, o.getJsonObject("params").getInt("a"));
	}

	/** 无参请求不含 params 字段。 */
	@Test
	public void requestWithoutParamsOmitsField() {
		McpRequest req = new McpRequest(2L, "ping", null);
		assertFalse(req.toJson().has("params"));
	}

	/** 通知无 id。 */
	@Test
	public void notificationHasNoId() {
		McpNotification n = new McpNotification("notifications/initialized");
		JsonObject o = n.toJson();
		assertEquals("2.0", o.getString("jsonrpc"));
		assertEquals("notifications/initialized", o.getString("method"));
		assertFalse(o.has("id"));
	}

	/** 成功响应解析 result。 */
	@Test
	public void responseSuccessParsed() {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}";
		McpResponse r = McpResponse.fromJson(Json.parse(json).getAsJsonObject());
		assertEquals(Long.valueOf(7), r.id());
		assertFalse(r.isError());
		assertNotNull(r.result());
		assertNull(r.error());
		assertTrue(r.result().getAsJsonObject().getBoolean("ok"));
	}

	/** 错误响应解析 error 对象。 */
	@Test
	public void responseErrorParsed() {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32601,\"message\":\"nope\"}}";
		McpResponse r = McpResponse.fromJson(Json.parse(json).getAsJsonObject());
		assertTrue(r.isError());
		assertEquals(-32601L, r.error().code());
		assertEquals("nope", r.error().message());
	}

	/** McpError 常量。 */
	@Test
	public void errorConstants() {
		assertEquals(-32700L, McpError.PARSE_ERROR);
		assertEquals(-32603L, McpError.INTERNAL_ERROR);
	}
}
