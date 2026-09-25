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
package com.sure.ai.mcp.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;

/**
 * {@link StreamableHttpMcpTransport} 测试：本地 HttpServer mock，零真实网络。
 *
 * @author sureai
 * @since 1.2.0
 */
public class StreamableHttpMcpTransportTest {

	private HttpServer server;
	private String endpoint;
	private final AtomicReference<String> lastSessionHeader = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.endpoint = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/mcp";
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** application/json 直返。 */
	@Test
	public void plainJsonResponse() {
		this.server.createContext("/mcp", ex -> {
			this.lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(ex, 200, "application/json",
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		McpResponse r = t.sendRequest(new McpRequest(1L, "initialize", null));
		assertTrue(r.result().getAsJsonObject().getBoolean("ok"));
		assertTrue(this.lastBody.get().contains("\"method\":\"initialize\""));
		t.close();
	}

	/** text/event-stream 响应按 SSE 聚合，并维护 Mcp-Session-Id。 */
	@Test
	public void sseResponseAndSessionId() {
		this.server.createContext("/mcp", ex -> {
			this.lastSessionHeader.set(ex.getRequestHeaders().getFirst("Mcp-Session-Id"));
			String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(reqBody);
			String id = m.find() ? m.group(1) : "0";
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.getResponseHeaders().set("Mcp-Session-Id", "sess-abc");
			byte[] body = ("event: message\n"
				+ "data: {\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"sid\":\"ok\"}}\n\n")
				.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		McpResponse r = t.sendRequest(new McpRequest(2L, "tools/list", null));
		assertEquals("ok", r.result().getAsJsonObject().getString("sid"));
		// 第二次请求应带上 session id
		McpResponse r2 = t.sendRequest(new McpRequest(3L, "tools/list", null));
		assertNotNull(r2);
		assertEquals("sess-abc", this.lastSessionHeader.get());
		t.close();
	}

	/** 非 2xx 抛异常。 */
	@Test(expected = com.sure.ai.exception.AiException.class)
	public void errorStatusThrows() {
		this.server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			respond(ex, 500, "application/json", "{\"oops\":true}");
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		t.sendRequest(new McpRequest(1L, "x", null));
	}

	private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}
}
