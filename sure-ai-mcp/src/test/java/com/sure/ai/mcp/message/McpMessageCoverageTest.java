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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpPromptResult;
import com.sure.ai.mcp.model.McpResourceContent;
import com.sure.ai.mcp.model.McpTool;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * MCP 消息与模型 record 的 {@code toString} / 安全解析分支补测。
 *
 * <p>覆盖既有测试未触达的 toString、fromElement 非对象退化、McpTool 缺 inputSchema 等防御分支。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpMessageCoverageTest {

	/** McpRequest.toString。 */
	@Test
	public void requestToString() {
		McpRequest r = new McpRequest(1L, "ping", null);
		assertNotNull(r.toString());
		assertTrue(r.toString().contains("ping"));
	}

	/** McpResponse.toString（含 error 与无 error 两种）。 */
	@Test
	public void responseToString() {
		McpResponse ok = new McpResponse(1L, Json.parse("{}"), null);
		assertTrue(ok.toString().contains("McpResponse"));
		McpResponse err = new McpResponse(1L, null, new McpError(-32601, "nope", null));
		assertTrue(err.toString().contains("error"));
	}

	/** McpError.toString。 */
	@Test
	public void errorToString() {
		McpError e = new McpError(-32603, "internal", null);
		assertTrue(e.toString().contains("-32603"));
	}

	/** McpError.fromJson 带 data 字段时保留 data。 */
	@Test
	public void errorFromJsonWithData() {
		JsonObject o = Json.object();
		o.put("code", -32600);
		o.put("message", "bad");
		JsonObject data = Json.object();
		data.put("field", "name");
		o.set("data", data);
		McpError e = McpError.fromJson(o);
		assertTrue(e.data().isObject());
		assertEquals("name", e.data().getAsJsonObject().getString("field"));
	}

	/** McpNotification.toString 与带 params 的序列化。 */
	@Test
	public void notificationToStringAndParams() {
		JsonObject p = Json.object();
		p.put("k", "v");
		McpNotification n = new McpNotification("notifications/x", p);
		assertTrue(n.toString().contains("notifications/x"));
		assertTrue(n.toJson().has("params"));
	}

	/** McpToolResult.fromElement 非对象退化为空结果。 */
	@Test
	public void toolResultFromElementNonObject() {
		McpToolResult r = McpToolResult.fromElement(Json.parse("[1,2,3]"));
		assertFalse(r.isError());
		assertFalse(r.hasText());
		assertEquals("", r.asText());
	}

	/** McpToolResult.fromResult 无 isError 字段时默认 false。 */
	@Test
	public void toolResultWithoutIsErrorDefaultsFalse() {
		JsonObject o = Json.object();
		o.set("content", Json.array());
		McpToolResult r = McpToolResult.fromResult(o);
		assertFalse(r.isError());
	}

	/** McpPromptResult.fromElement 非对象退化为空。 */
	@Test
	public void promptResultFromElementNonObject() {
		McpPromptResult r = McpPromptResult.fromElement(Json.parse("\"str\""));
		assertEquals(0, r.textContents().size());
	}

	/** McpResourceContent.fromElement 非对象退化为空列表。 */
	@Test
	public void resourceContentFromElementNonObject() {
		assertEquals(0, McpResourceContent.fromElement(Json.parse("42")).size());
	}

	/** McpTool.fromJson 缺 inputSchema 字段时为 null。 */
	@Test
	public void toolFromJsonWithoutInputSchema() {
		JsonObject o = Json.object();
		o.put("name", "t");
		o.put("description", "d");
		McpTool t = McpTool.fromJson(o);
		assertEquals("t", t.name());
		assertTrue(t.inputSchema() == null);
	}

	/** JsonElement 非对象入参防御。 */
	@Test
	public void nullAndPrimitiveElement() {
		JsonElement nil = null;
		assertEquals(0, McpToolResult.fromElement(nil).textContents().size());
		assertEquals(0, McpPromptResult.fromElement(nil).textContents().size());
		assertEquals(0, McpResourceContent.fromElement(nil).size());
	}
}
