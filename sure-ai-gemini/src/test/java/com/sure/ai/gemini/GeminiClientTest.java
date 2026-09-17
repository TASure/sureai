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

package com.sure.ai.gemini;

import static org.junit.Assert.assertEquals;
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
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.TextPart;

/**
 * {@link GeminiClient} 集成测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class GeminiClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastQuery = new AtomicReference<>();
	private final AtomicReference<String> lastPath = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1beta";
		this.lastBody.set(null);
		this.lastQuery.set(null);
		this.lastPath.set(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private GeminiClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("gemini-key-123").baseUrl(this.baseUrl).build();
		return new GeminiClient(cfg);
	}

	/** 注册处理器。 */
	private void handle(int status, String responseBody) {
		this.server.createContext("/", exchange -> {
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastQuery.set(exchange.getRequestURI().getQuery());
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

	/** 校验 key 查询参数 + generateContent 路径。 */
	@Test
	public void testKeyQueryParam() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"hi\"}]},\"finishReason\":\"STOP\"}],"
			+ "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"totalTokenCount\":5}}");
		GeminiClient client = newClient();
		client.chat(ChatRequest.builder().model("gemini-2.5-flash")
			.messages(ChatMessage.user("hello")).build());
		assertTrue("URL should contain ?key=", this.lastQuery.get().contains("key=gemini-key-123"));
		assertTrue("path should end with :generateContent",
			this.lastPath.get().endsWith(":generateContent"));
		client.close();
	}

	/** 请求体格式：contents + systemInstruction + generationConfig。 */
	@Test
	public void testRequestBodyFormat() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}");
		GeminiClient client = newClient();
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.system("Be concise."), ChatMessage.user("hi"))
			.temperature(0.5).maxTokens(256).topP(0.9).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"contents\""));
		assertTrue(body.contains("\"systemInstruction\""));
		assertTrue(body.contains("Be concise."));
		assertTrue(body.contains("\"generationConfig\""));
		assertTrue(body.contains("\"temperature\":0.5"));
		assertTrue(body.contains("\"maxOutputTokens\":256"));
		assertTrue(body.contains("\"topP\":0.9"));
		client.close();
	}

	/** 非流式响应解析。 */
	@Test
	public void testNonStreamParse() {
		String resp = "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"The answer is 42\"}]},\"finishReason\":\"STOP\"}],"
			+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":7,\"totalTokenCount\":17}}";
		handle(200, resp);
		GeminiClient client = newClient();
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("q")).build());
		assertEquals("The answer is 42", result.firstText());
		assertEquals("STOP", result.choices().get(0).finishReason());
		assertEquals(10, result.usage().promptTokens());
		assertEquals(7, result.usage().completionTokens());
		assertEquals(17, result.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合：每个 data 是完整 JSON 对象。 */
	@Test
	public void testStreamSse() {
		String sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"Hel\"}]},\"index\":0}]}\n\n"
			+ "data: {\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"lo\"}]},\"index\":0}]}\n\n"
			+ "data: {\"candidates\":[{\"content\":{\"role\":\"model\"},"
			+ "\"finishReason\":\"STOP\",\"index\":0}]}\n\n";
		this.server.createContext("/", exchange -> {
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastQuery.set(exchange.getRequestURI().getQuery());
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		GeminiClient client = newClient();
		StringBuilder sb = new StringBuilder();
		String[] finish = new String[1];
		client.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
				if (chunk.finishReason() != null) {
					finish[0] = chunk.finishReason();
				}
			});
		assertEquals("Hello", sb.toString());
		assertEquals("STOP", finish[0]);
		assertTrue("stream path should contain alt=sse",
			this.lastQuery.get().contains("alt=sse"));
		assertTrue("key in query", this.lastQuery.get().contains("key="));
		client.close();
	}

	/** embedContent 向量请求。 */
	@Test
	public void testEmbed() {
		handle(200, "{\"embedding\":{\"values\":[0.1,0.2,0.3]}}");
		GeminiClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest("gemini-embedding-001", List.of("hello")));
		assertTrue("path should end with :embedContent",
			this.lastPath.get().endsWith(":embedContent"));
		assertTrue("body should contain content.parts", this.lastBody.get().contains("\"content\""));
		assertTrue(this.lastBody.get().contains("hello"));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 1e-6);
		client.close();
	}

	/** 400 错误映射为 AiApiException。 */
	@Test
	public void testBadRequest400() {
		handle(400, "{\"error\":{\"code\":400,\"message\":\"Invalid model\"}}");
		GeminiClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chat(
			ChatRequest.builder().model("bad-model").messages(ChatMessage.user("hi")).build()));
		assertEquals(400, e.getHttpStatus());
		assertTrue(e.getRawBody().contains("Invalid model"));
		client.close();
	}

	/** 多模态：ImagePart 映射为 inline_data。 */
	@Test
	public void testMultimodal() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"seen\"}]},\"finishReason\":\"STOP\"}]}");
		GeminiClient client = newClient();
		List<MessagePart> parts = List.of(TextPart.of("describe"), ImagePart.ofBase64("aGVsbG8=", "image/png"));
		client.chat(ChatRequest.builder().model("m").messages(ChatMessage.user(parts)).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"inline_data\""));
		assertTrue(body.contains("\"mime_type\":\"image/png\""));
		assertTrue(body.contains("aGVsbG8="));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("gemini", newClient().name());
	}
}
