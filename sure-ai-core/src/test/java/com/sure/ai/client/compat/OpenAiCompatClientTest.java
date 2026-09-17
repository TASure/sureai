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

package com.sure.ai.client.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * {@link OpenAiCompatClient} 集成测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class OpenAiCompatClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastOrg = new AtomicReference<>();
	private final AtomicInteger requestCount = new AtomicInteger();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1";
		this.lastBody.set(null);
		this.lastAuth.set(null);
		this.lastOrg.set(null);
		this.requestCount.set(0);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private OpenAiCompatClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("test-key").baseUrl(this.baseUrl).build();
		return new OpenAiCompatClient(cfg);
	}

	/** 注册默认处理器。 */
	private void handle(int status, String responseBody) {
		handle(exchange -> respond(exchange, status, responseBody));
	}

	/** 注册自定义处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.requestCount.incrementAndGet();
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			this.lastOrg.set(exchange.getRequestHeaders().getFirst("OpenAI-Organization"));
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			h.handle(exchange);
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

	/** 处理器函数式接口。 */
	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws IOException;
	}

	/** 正常 chat：校验请求头与请求体字段。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"c1\",\"model\":\"gpt\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		OpenAiCompatClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user("hi")).temperature(0.7).build());
		assertEquals("Bearer test-key", this.lastAuth.get());
		assertTrue(this.lastBody.get().contains("\"model\":\"gpt\""));
		assertTrue(this.lastBody.get().contains("\"temperature\":0.7"));
		assertTrue(this.lastBody.get().contains("\"role\":\"user\""));
		assertEquals("c1", resp.id());
		assertEquals("hello", resp.firstText());
		assertEquals(8, resp.usage().totalTokens());
		assertEquals("stop", resp.choices().get(0).finishReason());
		client.close();
	}

	/** 流式 chat：聚合 deltaText。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"He\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":\"llo\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		OpenAiCompatClient client = newClient();
		StringBuilder sb = new StringBuilder();
		String[] finish = new String[1];
		client.chatStream(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
				if (chunk.finishReason() != null) {
					finish[0] = chunk.finishReason();
				}
			});
		assertEquals("Hello", sb.toString());
		assertEquals("stop", finish[0]);
		client.close();
	}

	/** embeddings。 */
	@Test
	public void testEmbeddings() {
		handle(200, "{\"model\":\"emb\",\"data\":[{\"embedding\":[0.1,0.2,0.3]}],"
			+ "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":0,\"total_tokens\":2}}");
		OpenAiCompatClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest("emb", List.of("hi")));
		assertTrue(this.lastBody.get().contains("\"model\":\"emb\""));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 1e-6);
		client.close();
	}

	/** 429 重试后成功。 */
	@Test
	public void testRetry429() {
		handle(ex -> {
			int count = this.requestCount.get();
			if (count == 1) {
				ex.getResponseHeaders().set("Retry-After", "1");
				respond(ex, 429, "{\"error\":\"busy\"}");
			} else {
				respond(ex, 200, "{\"id\":\"c2\",\"choices\":[{\"index\":0,"
					+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
			}
		});
		OpenAiCompatClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build());
		assertEquals("ok", resp.firstText());
		assertEquals(2, this.requestCount.get());
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		OpenAiCompatClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 400 映射为 AiApiException。 */
	@Test
	public void testBadRequest400() {
		handle(400, "{\"error\":\"bad\"}");
		OpenAiCompatClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chat(
			ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build()));
		assertEquals(400, e.getHttpStatus());
		assertTrue(e.getRawBody().contains("bad"));
		client.close();
	}

	/** function calling：请求带 tools，响应带 tool_calls。 */
	@Test
	public void testFunctionCalling() {
		handle(200, "{\"id\":\"c3\",\"model\":\"gpt\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":null,"
			+ "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
			+ "\"function\":{\"name\":\"getWeather\",\"arguments\":\"{\\\"city\\\":\\\"X\\\"}\"}}]},"
			+ "\"finish_reason\":\"tool_calls\"}]}");
		OpenAiCompatClient client = newClient();
		ToolFunction fn = ToolFunction.of("getWeather", "天气", "{\"type\":\"object\"}");
		ChatResponse resp = client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user("北京天气"))
			.tools(List.of(ToolSpec.of(fn))).build());
		assertTrue(this.lastBody.get().contains("\"tools\""));
		assertTrue(this.lastBody.get().contains("getWeather"));
		List<ToolCall> calls = resp.choices().get(0).message().toolCalls();
		assertNotNull(calls);
		assertEquals("call_1", calls.get(0).id());
		assertEquals("getWeather", calls.get(0).name());
		assertTrue(calls.get(0).argumentsJson().contains("city"));
		client.close();
	}

	/** 多模态：user 消息带 ImagePart。 */
	@Test
	public void testMultimodal() {
		handle(200, "{\"id\":\"c4\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"see\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		List<MessagePart> parts = List.of(TextPart.of("描述"), ImagePart.ofUrl("http://img/x.png"));
		client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user(parts)).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"type\":\"image_url\""));
		assertTrue(body.contains("http://img/x.png"));
		assertTrue(body.contains("\"type\":\"text\""));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("openai-compat", newClient().name());
	}

	/** organization 头。 */
	@Test
	public void testOrganizationHeader() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\"stop\"}]}");
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).organization("org-1").build();
		OpenAiCompatClient client = new OpenAiCompatClient(cfg);
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build());
		assertEquals("org-1", this.lastOrg.get());
		client.close();
	}

	/** 便捷 chat(model, prompt)。 */
	@Test
	public void testConvenienceChat() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		assertEquals("yo", client.chat("gpt", "hi").firstText());
		client.close();
	}

	/** 便捷 embed(model, text)。 */
	@Test
	public void testConvenienceEmbed() {
		handle(200, "{\"model\":\"e\",\"data\":[{\"embedding\":[1.0]}],"
			+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"total_tokens\":1}}");
		OpenAiCompatClient client = newClient();
		assertEquals(1, client.embed("e", "hi").embeddings().size());
		client.close();
	}
}
