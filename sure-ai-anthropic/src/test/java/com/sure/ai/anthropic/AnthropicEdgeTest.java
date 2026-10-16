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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolCall;

/**
 * {@link AnthropicClient} / {@link AnthropicUtil} / {@link AnthropicBatchClient} 边界分支测试。
 *
 * <p>聚焦覆盖：多 system 消息换行拼接、temperature/top_p/stop_sequences 透传、显式
 * tool_choice、extra 字段透传、extractInputSchema 各分支、serializeMessage 直接单测、
 * thinking 块 text 兜底、流式 SSE 错误路径（网络异常/非 2xx/中断）、baseUrl 边界、
 * 流式工具调用事件序列、Util 懒加载、私有构造器、批处理轮询超时与中断。</p>
 *
 * @author sureai
 */
public class AnthropicEdgeTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port;
		this.lastBody.set(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private AnthropicClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("edge-key").baseUrl(this.baseUrl).build();
		return new AnthropicClient(cfg);
	}

	/** 注册普通 JSON 响应。 */
	private void handle(int status, String responseBody) {
		this.server.createContext("/", exchange -> {
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

	/** 构造一个最小成功响应。 */
	private static String okResponse() {
		return "{\"id\":\"m1\",\"model\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
			+ "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
	}

	/** 多 system 消息：之间用换行拼接。 */
	@Test
	public void testMultipleSystemMessagesJoinedWithNewline() {
		handle(200, okResponse());
		AnthropicClient client = newClient();
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.system("You are helpful."),
				ChatMessage.system("Answer in Chinese."),
				ChatMessage.user("hi")).build());
		String body = this.lastBody.get();
		assertTrue("system joined with newline",
			body.contains("\"system\":\"You are helpful.\\nAnswer in Chinese.\""));
		client.close();
	}

	/** temperature / top_p / stop_sequences 透传。 */
	@Test
	public void testTemperatureTopPStopTransmitted() {
		handle(200, okResponse());
		AnthropicClient client = newClient();
		client.chat(ChatRequest.builder().model("m").messages(ChatMessage.user("hi"))
			.temperature(0.7).topP(0.9).stop(List.of("END")).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"temperature\":0.7"));
		assertTrue(body.contains("\"top_p\":0.9"));
		assertTrue(body.contains("\"stop_sequences\":[\"END\"]"));
		client.close();
	}

	/** 显式 tool_choice（非结构化输出分支）。 */
	@Test
	public void testExplicitToolChoice() {
		handle(200, okResponse());
		AnthropicClient client = newClient();
		JsonObject choice = Json.object();
		choice.put("type", "auto");
		client.chat(ChatRequest.builder().model("m").messages(ChatMessage.user("hi"))
			.toolChoice(choice).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"tool_choice\""));
		assertTrue(body.contains("\"type\":\"auto\""));
		client.close();
	}

	/** extra 字段透传到顶级请求体。 */
	@Test
	public void testExtraFieldsTransmitted() {
		handle(200, okResponse());
		AnthropicClient client = newClient();
		JsonObject cache = Json.object();
		cache.put("type", "ephemeral");
		client.chat(ChatRequest.builder().model("m").messages(ChatMessage.user("hi"))
			.extra("cache_control", cache).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"cache_control\""));
		assertTrue(body.contains("\"ephemeral\""));
		client.close();
	}

	/** extractInputSchema：responseFormat 为对象但无 json_schema → 直接返回该对象。 */
	@Test
	public void testExtractInputSchemaPlainObject() {
		JsonObject format = Json.object();
		format.put("type", "object");
		JsonObject result = AnthropicClient.buildStructuredOutputTool(format);
		assertEquals("structured_output", result.get("name").getAsString());
		JsonObject schema = result.get("input_schema").getAsJsonObject();
		assertEquals("object", schema.get("type").getAsString());
	}

	/** extractInputSchema：json_schema 无内层 schema → 返回 json_schema 本身。 */
	@Test
	public void testExtractInputSchemaNoInnerSchema() {
		JsonObject format = Json.object();
		JsonObject js = Json.object();
		js.put("name", "answer");
		format.put("type", "json_schema");
		format.set("json_schema", js);
		JsonObject result = AnthropicClient.buildStructuredOutputTool(format);
		JsonObject schema = result.get("input_schema").getAsJsonObject();
		assertEquals("answer", schema.get("name").getAsString());
	}

	/** extractInputSchema：responseFormat 非对象 → 兜底 type=object。 */
	@Test
	public void testExtractInputSchemaFallback() {
		JsonObject result = AnthropicClient.buildStructuredOutputTool("just-a-string");
		JsonObject schema = result.get("input_schema").getAsJsonObject();
		assertEquals("object", schema.get("type").getAsString());
	}

	/** serializeMessage 直接单测：带 toolCallId 的消息构造 tool_result 块。 */
	@Test
	public void testSerializeMessageWithToolCallId() {
		ChatMessage m = ChatMessage.tool("toolu_1", "{\"a\":1}");
		JsonObject o = AnthropicClient.serializeMessage(m);
		assertEquals("tool", o.get("role").getAsString());
		String content = o.get("content").toString();
		assertTrue(content.contains("tool_result"));
		assertTrue(content.contains("toolu_1"));
	}

	/** thinking 块带 text 字段（无 thinking 字段）→ 兜底解析到 reasoningContent。 */
	@Test
	public void testThinkingBlockTextFallback() {
		String resp = "{\"id\":\"m\",\"model\":\"m\",\"content\":["
			+ "{\"type\":\"thinking\",\"text\":\"推理过程兜底\"},"
			+ "{\"type\":\"text\",\"text\":\"答案\"}],\"stop_reason\":\"end_turn\","
			+ "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
		handle(200, resp);
		AnthropicClient client = newClient();
		com.sure.ai.model.ChatResponse r = client.chat(
			ChatRequest.builder().model("m").messages(ChatMessage.user("q")).build());
		assertEquals("答案", r.firstText());
		assertEquals("推理过程兜底", r.choices().get(0).message().reasoningContent());
		client.close();
	}

	/** 流式非 2xx → mapError 抛出 AiApiException。 */
	@Test
	public void testStreamNon2xx() {
		this.server.createContext("/", exchange -> {
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			respond(exchange, 500, "{\"type\":\"error\",\"error\":{\"type\":\"server_error\"}}");
		});
		AnthropicClient client = newClient();
		assertThrows(AiApiException.class, () -> client.chatStream(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			c -> { }));
		client.close();
	}

	/** 流式网络不可达 → AiException。 */
	@Test
	public void testStreamNetworkError() {
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:1").build();
		AnthropicClient client = new AnthropicClient(cfg);
		assertThrows(AiException.class, () -> client.chatStream(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			c -> { }));
		client.close();
	}

	/** 流式线程中断 → AiException。 */
	@Test
	public void testStreamInterrupted() {
		handle(200, "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"x\"}}\n\n");
		AnthropicClient client = newClient();
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, () -> client.chatStream(
				ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
				c -> { }));
		} finally {
			Thread.interrupted();
		}
		client.close();
	}

	/** baseUrl 为空 → AiException。 */
	@Test
	public void testBaseUrlBlank() {
		AiConfig cfg = AiConfig.builder().apiKey("k").build();
		AnthropicClient client = new AnthropicClient(cfg);
		assertThrows(AiException.class, () -> client.chatStream(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			c -> { }));
		client.close();
	}

	/** baseUrl 尾部带斜杠 → 拼接去掉重复斜杠。 */
	@Test
	public void testBaseUrlTrailingSlash() {
		this.server.createContext("/messages", exchange -> {
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			respond(exchange, 200, okResponse());
		});
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl + "/").build();
		AnthropicClient client = new AnthropicClient(cfg);
		client.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			c -> { });
		assertTrue(this.lastBody.get().contains("\"stream\":true"));
		client.close();
	}

	/** 流式工具调用事件序列：content_block_start(tool_use) → input_json_delta → content_block_stop。 */
	@Test
	public void testStreamToolUseSequence() {
		String sse = "event: message_start\n"
			+ "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_t\",\"content\":[]}}\n\n"
			+ "event: content_block_start\n"
			+ "data: {\"type\":\"content_block_start\",\"index\":0,"
			+ "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\"}}\n\n"
			+ "event: content_block_delta\n"
			+ "data: {\"type\":\"content_block_delta\",\"index\":0,"
			+ "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}\n\n"
			+ "event: content_block_delta\n"
			+ "data: {\"type\":\"content_block_delta\",\"index\":0,"
			+ "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"SF\\\"}\"}}\n\n"
			+ "event: content_block_stop\n"
			+ "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
			+ "event: message_delta\n"
			+ "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n"
			+ "event: message_stop\n"
			+ "data: {\"type\":\"message_stop\"}\n\n";
		this.server.createContext("/", exchange -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		AnthropicClient client = newClient();
		List<ToolCall[]> captured = new ArrayList<>();
		client.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("w")).build(),
			chunk -> {
				if (chunk.toolCalls() != null && !chunk.toolCalls().isEmpty()) {
					captured.add(chunk.toolCalls().toArray(new ToolCall[0]));
				}
			});
		assertEquals(1, captured.size());
		ToolCall call = captured.get(0)[0];
		assertEquals("toolu_1", call.id());
		assertEquals("get_weather", call.name());
		assertTrue(call.argumentsJson().contains("SF"));
		client.close();
	}

	/** Util 懒加载：重置 HOLDER 后触发 loadFromEnv（环境变量未设置时抛异常，但覆盖到懒加载分支）。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilLoadFromEnv() throws Exception {
		Field f = AnthropicUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		SingletonHolder<AnthropicClient> holder = (SingletonHolder<AnthropicClient>) f.get(null);
		holder.reset();
		try {
			// 环境变量未设置时 loadFromEnv 读取 env → apiKey 为空 → AiConfig 构造抛异常
			assertThrows(RuntimeException.class, AnthropicUtil::client);
		} finally {
			holder.set(newClient());
		}
	}

	/** AnthropicModels 私有构造器不可实例化。 */
	@Test
	public void testModelsPrivateCtor() throws Exception {
		Constructor<AnthropicModels> c = AnthropicModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** 批处理 waitForCompletion：始终 in_progress → 超时抛 AiException。 */
	@Test
	public void testBatchWaitTimeout() {
		this.server.createContext("/v1/messages/batches", exchange -> {
			String body = "{\"id\":\"b1\",\"processing_status\":\"in_progress\","
				+ "\"request_counts\":{\"processing\":1,\"succeeded\":0,\"errored\":0}}";
			respond(exchange, 200, body);
		});
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build();
		AnthropicBatchClient client = new AnthropicBatchClient(cfg);
		assertThrows(AiException.class, () -> client.waitForCompletion("b1", 50L));
		client.close();
	}

	/** 批处理 sleepQuietly 中断分支。 */
	@Test
	public void testBatchSleepInterrupted() throws Exception {
		java.lang.reflect.Method m = AnthropicBatchClient.class
			.getDeclaredMethod("sleepQuietly", long.class);
		m.setAccessible(true);
		Thread.currentThread().interrupt();
		try {
			assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null, 1000L));
		} finally {
			Thread.interrupted();
		}
	}

	/** 流式 ping 事件：静默忽略（case ping 分支）。 */
	@Test
	public void testStreamPingEventIgnored() {
		String sse = "event: message_start\n"
			+ "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_p\",\"content\":[]}}\n\n"
			+ "event: ping\n"
			+ "data: {\"type\":\"ping\"}\n\n"
			+ "event: content_block_delta\n"
			+ "data: {\"type\":\"content_block_delta\",\"index\":0,"
			+ "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n"
			+ "event: message_delta\n"
			+ "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"
			+ "event: message_stop\n"
			+ "data: {\"type\":\"message_stop\"}\n\n";
		this.server.createContext("/", exchange -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		AnthropicClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("p")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("hi", sb.toString());
		client.close();
	}
}
