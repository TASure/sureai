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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;
import com.sure.ai.mcp.model.McpPrompt;
import com.sure.ai.mcp.model.McpResource;
import com.sure.ai.mcp.model.McpTool;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.mcp.transport.McpTransport;

/**
 * {@link McpClient} 握手与能力 API 测试：脚本化假传输，零真实 IO。
 *
 * @author sureai
 * @since 1.2.0
 */
public class McpClientTest {

	/** 脚本化假传输。 */
	private static final class FakeTransport implements McpTransport {
		final List<String> methods = new ArrayList<>();
		final List<String> notifications = new ArrayList<>();
		private boolean open = true;

		@Override
		public McpResponse sendRequest(McpRequest request) {
			this.methods.add(request.method());
			switch (request.method()) {
				case "initialize":
					return ok(request.id(), "{\"protocolVersion\":\"2025-06-18\","
						+ "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0\"}}");
				case "tools/list":
					return ok(request.id(), "{\"tools\":[{\"name\":\"add\",\"description\":\"加法\","
						+ "\"inputSchema\":{\"type\":\"object\"}}]}");
				case "tools/call":
					return ok(request.id(), "{\"content\":[{\"type\":\"text\",\"text\":\"42\"}],\"isError\":false}");
				case "resources/list":
					return ok(request.id(), "{\"resources\":[{\"uri\":\"file:///a.txt\",\"name\":\"a.txt\"}]}");
				case "resources/read":
					return ok(request.id(), "{\"contents\":[{\"uri\":\"file:///a.txt\",\"text\":\"hello\"}]}");
				case "prompts/list":
					return ok(request.id(), "{\"prompts\":[{\"name\":\"greet\",\"description\":\"问候\"}]}");
				case "prompts/get":
					return ok(request.id(), "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}]}");
				case "boom":
					return new McpResponse(request.id(), null,
						new com.sure.ai.mcp.message.McpError(-32603, "boom", null));
				default:
					return new McpResponse(request.id(), null,
						new com.sure.ai.mcp.message.McpError(-32601, "no", null));
			}
		}

		private static McpResponse ok(long id, String resultJson) {
			return new McpResponse(id, Json.parse(resultJson), null);
		}

		@Override
		public void sendNotification(McpNotification notification) {
			this.notifications.add(notification.method());
		}

		@Override
		public void close() {
			this.open = false;
		}

		@Override
		public boolean isOpen() {
			return this.open;
		}
	}

	/** 握手流程：initialize 请求 + initialized 通知。 */
	@Test
	public void handshakeSendsInitializeAndInitialized() {
		FakeTransport t = new FakeTransport();
		McpClient c = new McpClient(t, McpClient.DEFAULT_PROTOCOL_VERSION);
		assertEquals("initialize", t.methods.get(0));
		assertTrue(t.notifications.contains("notifications/initialized"));
		assertFalse(c.isClosed());
		c.close();
		assertTrue(c.isClosed());
		assertTrue(t.notifications.contains("notifications/closed"));
	}

	/** tools/list 解析。 */
	@Test
	public void toolsListParsed() {
		McpClient c = new McpClient(new FakeTransport(), McpClient.DEFAULT_PROTOCOL_VERSION);
		List<McpTool> tools = c.toolsList();
		assertEquals(1, tools.size());
		assertEquals("add", tools.get(0).name());
		c.close();
	}

	/** tools/call 解析结果。 */
	@Test
	public void toolsCallParsed() {
		McpClient c = new McpClient(new FakeTransport(), McpClient.DEFAULT_PROTOCOL_VERSION);
		McpToolResult r = c.toolsCall("add", Json.object());
		assertFalse(r.isError());
		assertEquals("42", r.asText());
		c.close();
	}

	/** resources / prompts API。 */
	@Test
	public void resourcesAndPrompts() {
		McpClient c = new McpClient(new FakeTransport(), McpClient.DEFAULT_PROTOCOL_VERSION);
		List<McpResource> rs = c.resourcesList();
		assertEquals("file:///a.txt", rs.get(0).uri());
		assertEquals(1, c.resourcesRead("file:///a.txt").size());
		List<McpPrompt> ps = c.promptsList();
		assertEquals("greet", ps.get(0).name());
		assertEquals("hi", c.promptsGet("greet", null).asText());
		c.close();
	}

	/** 错误响应抛 AiException。 */
	@Test(expected = AiException.class)
	public void errorResponseThrows() {
		McpClient c = new McpClient(new FakeTransport(), McpClient.DEFAULT_PROTOCOL_VERSION);
		try {
			c.call("boom", null);
		} finally {
			c.close();
		}
	}

	/** 关闭后调用抛异常。 */
	@Test(expected = AiException.class)
	public void closedClientRejects() {
		McpClient c = new McpClient(new FakeTransport(), McpClient.DEFAULT_PROTOCOL_VERSION);
		c.close();
		c.call("tools/list", null);
	}

	/** 便捷构造注入 transport 的 Builder。 */
	@Test
	public void builderWithTransport() {
		FakeTransport t = new FakeTransport();
		McpClient c = builderForTest(t);
		assertTrue(t.methods.contains("initialize"));
		c.close();
	}

	/** 构造带注入传输的客户端（包内可见）。 */
	static McpClient builderForTest(McpTransport t) {
		return McpClient.stdio("x").timeout(java.time.Duration.ofSeconds(1)).transport(t).build();
	}
}
