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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.agent.tool.catalog.ConflictStrategy;
import com.sure.ai.agent.tool.catalog.ToolCatalog;
import com.sure.ai.agent.tool.catalog.ToolEntry;
import com.sure.ai.agent.tool.catalog.ToolSource;
import com.sure.ai.internal.json.Json;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;
import com.sure.ai.mcp.transport.McpTransport;

/**
 * {@link McpToolAdapter} 向 {@link ToolCatalog} 聚合测试：假 MCP server，零网络。
 */
public class McpToolCatalogAdapterTest {

	/** 最小假传输：tools/list 返回 echo/add，tools/call 回显。 */
	private static final class FakeTransport implements McpTransport {
		final List<String> methods = new ArrayList<>();

		@Override
		public McpResponse sendRequest(McpRequest request) {
			this.methods.add(request.method());
			switch (request.method()) {
				case "initialize":
					return new McpResponse(request.id(),
						Json.parse("{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{}}"), null);
				case "tools/list":
					return new McpResponse(request.id(), Json.parse("{\"tools\":["
						+ "{\"name\":\"echo\",\"description\":\"回显\","
						+ "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}},"
						+ "{\"name\":\"add\",\"description\":\"求和\","
						+ "\"inputSchema\":{\"type\":\"object\"}}]}"), null);
				case "tools/call":
					String text = request.params().getJsonObject("arguments").optString("text", "");
					return new McpResponse(request.id(), Json.parse("{\"content\":[{"
						+ "\"type\":\"text\",\"text\":\"echo:" + text + "\"}],\"isError\":false}"), null);
				default:
					return new McpResponse(request.id(), null,
						new com.sure.ai.mcp.message.McpError(-32601, "no", null));
			}
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

	@Test
	public void registerIntoCatalogNamespacedAndInvoke() throws Exception {
		McpClient client = McpClient.stdio("x").transport(new FakeTransport()).build();
		ToolCatalog catalog = new ToolCatalog();
		int n = McpToolAdapter.registerAllTools(client, catalog, "demo-server",
			ConflictStrategy.OVERWRITE, true);
		assertEquals(2, n);

		ToolEntry echo = catalog.get("mcp.demo-server.echo").orElseThrow();
		assertEquals(ToolSource.MCP, echo.metadata().source());
		assertEquals("demo-server", echo.metadata().server());
		assertTrue(catalog.get("mcp.demo-server.add").isPresent());

		com.sure.ai.internal.json.JsonObject args = Json.object();
		args.set("text", "hi");
		String out = echo.handler().execute(args);
		assertEquals("echo:hi", out);
		client.close();
	}

	@Test
	public void registerIntoCatalogBareNames() throws Exception {
		McpClient client = McpClient.stdio("x").transport(new FakeTransport()).build();
		ToolCatalog catalog = new ToolCatalog();
		int n = McpToolAdapter.registerAllTools(client, catalog, "demo-server",
			ConflictStrategy.OVERWRITE, false);
		assertEquals(2, n);
		assertTrue(catalog.get("echo").isPresent());
		assertTrue(catalog.get("mcp.demo-server.echo").isEmpty());
		client.close();
	}
}
