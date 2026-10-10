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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.exception.AiException;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;

/**
 * {@link StreamableHttpMcpTransport} 补测：覆盖 sendNotification、null 超时、
 * 对端不可达、非 JSON 响应体、SSE 流未命中 id 与 isOpen 状态。
 *
 * <p>全部走本地回环 HttpServer，零外网。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class StreamableHttpMcpTransportExtraTest {

	private HttpServer server;
	private String endpoint;

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

	private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 通知发送成功（2xx 应答）不抛异常。 */
	@Test
	public void sendNotificationAccepted() {
		this.server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			respond(ex, 202, "application/json", "{}");
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		t.sendNotification(new McpNotification("notifications/initialized"));
		t.close();
	}

	/** 通知发送失败（连接拒绝）抛 AiException。 */
	@Test(expected = AiException.class)
	public void sendNotificationFailureThrows() {
		this.server.stop(0);
		// 随机找一个大概率未监听的端口
		String dead = "http://127.0.0.1:1/mcp";
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(dead, Duration.ofMillis(300));
		t.sendNotification(new McpNotification("x"));
	}

	/** timeout 传 null 时使用默认超时。 */
	@Test
	public void nullTimeoutFallsBackToDefault() {
		this.server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			respond(ex, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, null);
		assertNotNull(t.sendRequest(new McpRequest(1L, "initialize", null)));
		t.close();
	}

	/** 对端不可达时 sendRequest 抛 AiException。 */
	@Test(expected = AiException.class)
	public void unreachableHostThrowsAiException() {
		this.server.stop(0);
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(
			"http://127.0.0.1:1/mcp", Duration.ofMillis(300));
		t.sendRequest(new McpRequest(1L, "x", null));
	}

	/** 200 但响应体是 JSON 数组（非对象）时抛解析 AiException。 */
	@Test(expected = AiException.class)
	public void nonJsonObjectResponseBodyThrows() {
		this.server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			respond(ex, 200, "application/json", "[1,2,3]");
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		t.sendRequest(new McpRequest(1L, "x", null));
	}

	/** SSE 事件数据为 JSON 数组（非对象）时被忽略。 */
	@Test
	public void sseNonObjectEventIgnoredThenMatch() {
		this.server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			String sse = "event: message\ndata: [1,2,3]\n\n"
				+ "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}\n\n";
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			byte[] body = sse.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		com.sure.ai.mcp.message.McpResponse r = t.sendRequest(new McpRequest(7L, "x", null));
		assertTrue(r.result().getAsJsonObject().getBoolean("ok"));
		t.close();
	}

	/** SSE 事件数据为对象但无 id/result/error，以及有 id 无 result 的服务端请求帧，均被忽略。 */
	@Test
	public void sseEventWithoutIdOrResultIgnoredThenMatch() {
		this.server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			String sse = "event: message\ndata: {\"method\":\"some/event\"}\n\n"
				+ "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/ping\"}\n\n"
				+ "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{\"v\":1}}\n\n";
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			byte[] body = sse.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		com.sure.ai.mcp.message.McpResponse r = t.sendRequest(new McpRequest(8L, "x", null));
		assertEquals(1, r.result().getAsJsonObject().getInt("v"));
		t.close();
	}

	/** SSE 事件数据为非法 JSON 时被忽略。 */
	@Test
	public void sseMalformedEventIgnoredThenMatch() {
		this.server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			String sse = "event: message\ndata: {not-json\n\n"
				+ "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"w\":2}}\n\n";
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			byte[] body = sse.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		com.sure.ai.mcp.message.McpResponse r = t.sendRequest(new McpRequest(9L, "x", null));
		assertEquals(2, r.result().getAsJsonObject().getInt("w"));
		t.close();
	}

	/** SSE 流中找不到匹配 id 的响应时抛 AiException。 */
	@Test(expected = AiException.class)
	public void sseMissingMatchingIdThrows() {
		this.server.createContext("/mcp", ex -> {
			ex.getRequestBody().readAllBytes();
			String sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}\n\n";
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			byte[] body = sse.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
		});
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		t.sendRequest(new McpRequest(1L, "x", null));
	}

	/** 关闭后 isOpen 返回 false。 */
	@Test
	public void closeSetsOpenFalse() {
		StreamableHttpMcpTransport t = new StreamableHttpMcpTransport(this.endpoint, Duration.ofSeconds(5));
		assertTrue(t.isOpen());
		t.close();
		assertFalse(t.isOpen());
	}
}
