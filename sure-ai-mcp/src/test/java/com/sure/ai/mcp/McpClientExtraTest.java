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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;
import com.sure.ai.mcp.model.McpPromptResult;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.mcp.transport.McpTransport;

/**
 * {@link McpClient} 与 Builder 分支补测：覆盖 toolsCall 空参数、promptsGet 带参数、
 * close 幂等与异常吞掉、Builder 各 setter 及 build() 缺传输抛错。
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpClientExtraTest {

	/** 脚本化假传输：initialize 握手后按 method 回结果。 */
	private static final class FakeTransport implements McpTransport {
		private boolean open = true;
		private final boolean throwOnCloseNotification;

		FakeTransport() {
			this(false);
		}

		FakeTransport(boolean throwOnCloseNotification) {
			this.throwOnCloseNotification = throwOnCloseNotification;
		}

		@Override
		public McpResponse sendRequest(McpRequest request) {
			String result = switch (request.method()) {
				case "initialize" -> "{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{}}";
				case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}";
				case "prompts/get" -> "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}]}";
				default -> "{}";
			};
			return new McpResponse(request.id(), Json.parse(result), null);
		}

		@Override
		public void sendNotification(McpNotification notification) {
			if (this.throwOnCloseNotification && "notifications/closed".equals(notification.method())) {
				throw new IllegalStateException("notification down");
			}
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

	/** toolsCall 传 null arguments 时退化为空对象。 */
	@Test
	public void toolsCallNullArgumentsAccepted() {
		McpClient c = new McpClient(new FakeTransport(), McpClient.DEFAULT_PROTOCOL_VERSION);
		McpToolResult result = c.toolsCall("add", null);
		assertNotNull(result);
		assertFalse(result.isError());
		c.close();
	}

	/** promptsGet 带非空 arguments 时应透传。 */
	@Test
	public void promptsGetWithArgumentsPassesThrough() {
		McpClient c = new McpClient(new FakeTransport(), McpClient.DEFAULT_PROTOCOL_VERSION);
		Map<String, Object> args = new HashMap<>();
		args.put("name", "world");
		McpPromptResult r = c.promptsGet("greet", args);
		assertEquals("hi", r.asText());
		c.close();
	}

	/** close() 重复调用幂等，不重复发 closed 通知。 */
	@Test
	public void closeIsIdempotent() {
		FakeTransport t = new FakeTransport();
		McpClient c = new McpClient(t, McpClient.DEFAULT_PROTOCOL_VERSION);
		c.close();
		c.close();
		assertTrue(c.isClosed());
	}

	/** close() 时发送 closed 通知失败不应影响传输关闭。 */
	@Test
	public void closeNotificationFailureIsSwallowed() {
		FakeTransport t = new FakeTransport(true);
		McpClient c = new McpClient(t, McpClient.DEFAULT_PROTOCOL_VERSION);
		c.close();
		assertTrue(c.isClosed());
	}

	/** Builder 未指定任何传输时 build 抛 AiException。 */
	@Test(expected = AiException.class)
	public void buildWithoutTransportThrows() throws Exception {
		var ctor = McpClient.Builder.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		McpClient.Builder empty = (McpClient.Builder) ctor.newInstance();
		empty.build();
	}

	/** Builder 的 setter 链式返回自身。 */
	@Test
	public void builderSettersChain() {
		McpClient.Builder b = McpClient.stdio("node")
			.arg("a")
			.args(null)
			.protocolVersion("2026-07-28")
			.timeout(Duration.ofSeconds(3));
		assertNotNull(b);
	}

	/** McpClient.http(url) 应构造指向本地回环端点的客户端并完成握手。 */
	@Test
	public void httpBuilderBuildsAgainstLocalLoopback() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
				+ "\"serverInfo\":{\"name\":\"loop\",\"version\":\"1\"}}}").getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
			ex.close();
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
			McpClient c = McpClient.http(url).timeout(Duration.ofSeconds(5)).build();
			assertNotNull(c);
			c.close();
		} finally {
			server.stop(0);
		}
	}

	/** McpClient.http(url) 工厂方法返回 Builder。 */
	@Test
	public void httpFactoryReturnsBuilder() {
		assertNotNull(McpClient.http("http://127.0.0.1:1/mcp"));
	}
}
