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

package com.sure.ai.ollama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * {@link OllamaClient} 集成测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class OllamaClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastPath = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port;
		this.lastBody.set(null);
		this.lastAuth.set(null);
		this.lastPath.set(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private OllamaClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("ollama-local").baseUrl(this.baseUrl).build();
		return new OllamaClient(cfg);
	}

	/** 注册处理器。 */
	private void handle(int status, String responseBody) {
		this.server.createContext("/", exchange -> {
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
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

	/** 无鉴权头校验。 */
	@Test
	public void testNoAuthHeader() {
		handle(200, "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
			+ "\"done\":true,\"done_reason\":\"stop\"}");
		OllamaClient client = newClient();
		client.chat(ChatRequest.builder().model("llama3.2").messages(ChatMessage.user("hello")).build());
		assertNull("no Authorization header", this.lastAuth.get());
		assertEquals("/api/chat", this.lastPath.get());
		client.close();
	}

	/** /api/chat 请求体格式：model + messages + stream + options。 */
	@Test
	public void testChatRequestBody() {
		handle(200, "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
			+ "\"done\":true,\"done_reason\":\"stop\"}");
		OllamaClient client = newClient();
		client.chat(ChatRequest.builder().model("qwen2.5")
			.messages(ChatMessage.user("hi"))
			.temperature(0.8).maxTokens(512).topP(0.95).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"model\":\"qwen2.5\""));
		assertTrue(body.contains("\"stream\":false"));
		assertTrue(body.contains("\"options\""));
		assertTrue(body.contains("\"temperature\":0.8"));
		assertTrue(body.contains("\"num_predict\":512"));
		assertTrue(body.contains("\"top_p\":0.95"));
		assertTrue(body.contains("\"role\":\"user\""));
		client.close();
	}

	/** 非流式响应解析：message.content + done_reason + usage 映射。 */
	@Test
	public void testNonStreamParse() {
		String resp = "{\"model\":\"llama3.2\",\"created_at\":\"2026-01-01T00:00:00Z\","
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"The sky is blue.\"},"
			+ "\"done\":true,\"done_reason\":\"stop\","
			+ "\"prompt_eval_count\":15,\"eval_count\":8}";
		handle(200, resp);
		OllamaClient client = newClient();
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("why")).build());
		assertEquals("llama3.2", result.model());
		assertEquals("The sky is blue.", result.firstText());
		assertEquals("stop", result.choices().get(0).finishReason());
		assertEquals(15, result.usage().promptTokens());
		assertEquals(8, result.usage().completionTokens());
		assertEquals(23, result.usage().totalTokens());
		client.close();
	}

	/** NDJSON 流式聚合：逐行 JSON。 */
	@Test
	public void testStreamNdjson() {
		String ndjson = "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"done\":false}\n"
			+ "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"lo\"},\"done\":false}\n"
			+ "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}\n";
		this.server.createContext("/", exchange -> {
			this.lastPath.set(exchange.getRequestURI().getPath());
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			byte[] bytes = ndjson.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		OllamaClient client = newClient();
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
		assertEquals("stop", finish[0]);
		assertTrue("stream=true in request", this.lastBody.get().contains("\"stream\":true"));
		client.close();
	}

	/** /api/embed 向量请求与响应。 */
	@Test
	public void testEmbed() {
		handle(200, "{\"model\":\"mxbai-embed\",\"embeddings\":[[0.1,0.2,0.3]]}");
		OllamaClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest("mxbai-embed", List.of("hello")));
		assertEquals("/api/embed", this.lastPath.get());
		assertTrue(this.lastBody.get().contains("\"input\""));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 1e-6);
		client.close();
	}

	/** 错误响应映射。 */
	@Test
	public void testErrorResponse() {
		handle(500, "{\"error\":\"model not found\"}");
		OllamaClient client = newClient();
		com.sure.ai.exception.AiApiException e = org.junit.Assert.assertThrows(
			com.sure.ai.exception.AiApiException.class, () -> client.chat(
				ChatRequest.builder().model("nonexistent").messages(ChatMessage.user("hi")).build()));
		assertEquals(500, e.getHttpStatus());
		assertTrue(e.getRawBody().contains("model not found"));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("ollama", newClient().name());
	}

	/** 便捷 chat(model, prompt)。 */
	@Test
	public void testConvenienceChat() {
		handle(200, "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},"
			+ "\"done\":true,\"done_reason\":\"stop\"}");
		OllamaClient client = newClient();
		assertEquals("yo", client.chat("m", "hi").firstText());
		client.close();
	}
}
