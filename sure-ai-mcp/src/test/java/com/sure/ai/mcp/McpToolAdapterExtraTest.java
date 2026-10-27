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
package com.sure.ai.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;
import com.sure.ai.mcp.transport.McpTransport;

/**
 * {@link McpToolAdapter} 补测：覆盖工具缺 inputSchema/description 的 toFunction 分支、
 * handler 错误结果与无文本结果分支，以及私有构造器。
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpToolAdapterExtraTest {

	/** 假传输：tools/list 返回三个边界工具。 */
	private static final class FakeTransport implements McpTransport {
		@Override
		public McpResponse sendRequest(McpRequest request) {
			return switch (request.method()) {
				case "initialize" -> new McpResponse(request.id(),
					Json.parse("{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{}}"), null);
				case "tools/list" -> new McpResponse(request.id(), Json.parse("{\"tools\":["
					+ "{\"name\":\"noschema\"},"
					+ "{\"name\":\"errtool\",\"description\":\"坏工具\"},"
					+ "{\"name\":\"notext\",\"description\":\"无文本\"}"
					+ "]}"), null);
				case "tools/call" -> {
					String name = request.params().optString("name", "");
					String body = switch (name) {
						case "errtool" -> "{\"content\":[{\"type\":\"text\",\"text\":\"bad thing\"}],\"isError\":true}";
						case "notext" -> "{\"content\":[{\"type\":\"image\",\"data\":\"aa\"}],\"isError\":false}";
						default -> "{\"content\":[],\"isError\":false}";
					};
					yield new McpResponse(request.id(), Json.parse(body), null);
				}
				default -> new McpResponse(request.id(), null,
					new com.sure.ai.mcp.message.McpError(-32601, "no", null));
			};
		}

		@Override
		public void sendNotification(McpNotification notification) {
		}

		@Override
		public void close() {
		}

		@Override
		public boolean isOpen() {
			return true;
		}
	}

	/** 缺 inputSchema 时 schema 退化为 {}，缺 description 退化为空串。 */
	@Test
	public void toolWithoutSchemaOrDescription() {
		McpClient client = McpClient.stdio("x").transport(new FakeTransport()).build();
		ToolRegistry registry = new ToolRegistry();
		int n = McpToolAdapter.registerAllTools(client, registry);
		assertEquals(3, n);
		String schema = registry.getFunction("noschema").orElseThrow().parameters();
		assertEquals("{}", schema);
		client.close();
	}

	/** 错误工具结果回灌「MCP 工具执行出错:」前缀。 */
	@Test
	public void errorResultWrapped() throws Exception {
		McpClient client = McpClient.stdio("x").transport(new FakeTransport()).build();
		ToolRegistry registry = new ToolRegistry();
		McpToolAdapter.registerAllTools(client, registry);
		ToolHandler h = registry.getHandler("errtool").orElseThrow();
		JsonObject args = Json.object();
		String out = h.execute(args);
		assertTrue(out, out.startsWith("MCP 工具执行出错:"));
		client.close();
	}

	/** 无文本内容时回退「（无返回内容）」。 */
	@Test
	public void noTextResultFallback() throws Exception {
		McpClient client = McpClient.stdio("x").transport(new FakeTransport()).build();
		ToolRegistry registry = new ToolRegistry();
		McpToolAdapter.registerAllTools(client, registry);
		ToolHandler h = registry.getHandler("notext").orElseThrow();
		JsonObject args = Json.object();
		assertEquals("（无返回内容）", h.execute(args));
		client.close();
	}

	/** 私有构造器应抛 AssertionError。 */
	@Test
	public void privateConstructorThrows() {
		try {
			var ctor = McpToolAdapter.class.getDeclaredConstructor();
			ctor.setAccessible(true);
			ctor.newInstance();
			assertTrue("应抛 AssertionError", false);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			assertEquals(AssertionError.class, ex.getCause().getClass());
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}
}
