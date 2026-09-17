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

package com.sure.ai.anthropic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * {@link AnthropicClient} 集成测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class AnthropicClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastApiKey = new AtomicReference<>();
	private final AtomicReference<String> lastVersion = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port;
		this.lastBody.set(null);
		this.lastApiKey.set(null);
		this.lastVersion.set(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private AnthropicClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("test-anthropic-key").baseUrl(this.baseUrl).build();
		return new AnthropicClient(cfg);
	}

	/** 注册处理器。 */
	private void handle(int status, String responseBody) {
		this.server.createContext("/", exchange -> {
			this.lastApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
			this.lastVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			respond(exchange, status, responseBody);
		});
	}

	/** 发送 JSON 响应。 */
	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 鉴权头校验：x-api-key + anthropic-version。 */
	@Test
	public void testAuthHeaders() {
		handle(200, "{\"id\":\"msg_1\",\"model\":\"claude-sonnet-4-6\",\"role\":\"assistant\","
			+ "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
			+ "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}");
		AnthropicClient client = newClient();
		client.chat(ChatRequest.builder().model("claude-sonnet-4-6")
			.messages(ChatMessage.user("hello")).build());
		assertEquals("test-anthropic-key", this.lastApiKey.get());
		assertEquals("2023-06-01", this.lastVersion.get());
		client.close();
	}

	/** system 顶级字段 + max_tokens 必填。 */
	@Test
	public void testSystemAndMaxTokens() {
		handle(200, "{\"id\":\"msg_2\",\"model\":\"m\",\"role\":\"assistant\","
			+ "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
			+ "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
		AnthropicClient client = newClient();
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.system("You are helpful."), ChatMessage.user("hi")).build());
		String body = this.lastBody.get();
		assertTrue("system should be top-level", body.contains("\"system\":\"You are helpful.\""));
		assertTrue("max_tokens required", body.contains("\"max_tokens\":1024"));
		assertTrue("messages should not contain system role", !body.contains("\"role\":\"system\""));
		client.close();
	}

	/** 非流式响应解析：text 块拼接 + tool_use 提取。 */
	@Test
	public void testNonStreamParse() {
		String resp = "{\"id\":\"msg_3\",\"model\":\"claude-haiku-4-5\",\"role\":\"assistant\","
			+ "\"content\":["
			+ "{\"type\":\"text\",\"text\":\"The answer is \"},"
			+ "{\"type\":\"text\",\"text\":\"42\"}"
			+ "],\"stop_reason\":\"end_turn\","
			+ "\"usage\":{\"input_tokens\":10,\"output_tokens\":7}}";
		handle(200, resp);
		AnthropicClient client = newClient();
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("q")).build());
		assertEquals("msg_3", result.id());
		assertEquals("The answer is 42", result.firstText());
		assertEquals("end_turn", result.choices().get(0).finishReason());
		assertEquals(10, result.usage().promptTokens());
		assertEquals(7, result.usage().completionTokens());
		assertEquals(17, result.usage().totalTokens());
		client.close();
	}

	/** SSE 事件序列聚合：message_start → content_block_start → delta×2 → stop → message_delta → message_stop。 */
	@Test
	public void testStreamSseEvents() {
		String sse = "event: message_start\n"
			+ "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_s1\",\"model\":\"claude-sonnet-4-6\","
			+ "\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}\n\n"
			+ "event: content_block_start\n"
			+ "data: {\"type\":\"content_block_start\",\"index\":0,"
			+ "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
			+ "event: content_block_delta\n"
			+ "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n"
			+ "event: content_block_delta\n"
			+ "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"!\"}}\n\n"
			+ "event: content_block_stop\n"
			+ "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
			+ "event: message_delta\n"
			+ "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
			+ "\"usage\":{\"output_tokens\":15}}\n\n"
			+ "event: message_stop\n"
			+ "data: {\"type\":\"message_stop\"}\n\n";
		this.server.createContext("/", exchange -> {
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		AnthropicClient client = newClient();
		StringBuilder sb = new StringBuilder();
		String[] finish = new String[1];
		String[] id = new String[1];
		client.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.id() != null && id[0] == null) {
					id[0] = chunk.id();
				}
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
				if (chunk.finishReason() != null) {
					finish[0] = chunk.finishReason();
				}
			});
		assertEquals("Hello!", sb.toString());
		assertEquals("end_turn", finish[0]);
		assertEquals("msg_s1", id[0]);
		assertTrue("stream=true in request", this.lastBody.get().contains("\"stream\":true"));
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid key\"}}");
		AnthropicClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 400 映射为 AiApiException。 */
	@Test
	public void testBadRequest400() {
		handle(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}");
		AnthropicClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chat(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build()));
		assertEquals(400, e.getHttpStatus());
		assertTrue(e.getRawBody().contains("bad"));
		client.close();
	}

	/** 工具调用：请求 tools 格式 + 响应 tool_use 解析。 */
	@Test
	public void testToolUse() {
		String resp = "{\"id\":\"msg_t\",\"model\":\"m\",\"role\":\"assistant\","
			+ "\"content\":["
			+ "{\"type\":\"text\",\"text\":\"Let me check.\"},"
			+ "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"SF\"}}"
			+ "],\"stop_reason\":\"tool_use\","
			+ "\"usage\":{\"input_tokens\":20,\"output_tokens\":15}}";
		handle(200, resp);
		AnthropicClient client = newClient();
		ToolFunction fn = ToolFunction.of("get_weather", "Get weather", "{\"type\":\"object\"}");
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("weather in SF"))
			.tools(List.of(ToolSpec.of(fn))).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"input_schema\""));
		assertTrue(body.contains("get_weather"));
		List<ToolCall> calls = result.choices().get(0).message().toolCalls();
		assertNotNull(calls);
		assertEquals("toolu_1", calls.get(0).id());
		assertEquals("get_weather", calls.get(0).name());
		assertTrue(calls.get(0).argumentsJson().contains("SF"));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("anthropic", newClient().name());
	}
}
