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

package com.sure.ai.cohere;

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
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * {@link CohereClient} 测试：本地 HttpServer mock chat / embed 接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class CohereClientTest {

	private static final String API_KEY = "test-cohere-key";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastChatBody = new AtomicReference<>();
	private final AtomicReference<String> lastEmbedBody = new AtomicReference<>();
	private final AtomicReference<String> authHeader = new AtomicReference<>();

	/** 启动本地服务，注册 chat/embed 响应。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port;
		this.lastChatBody.set(null);
		this.lastEmbedBody.set(null);
		this.authHeader.set(null);
		registerHandlers();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			this.authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
			if (path.equals("/chat")) {
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.lastChatBody.set(new String(in, StandardCharsets.UTF_8));
				String req = this.lastChatBody.get();
				if (req.contains("\"stream\":true")) {
					String sse = "data: {\"type\":\"content-delta\",\"delta\":{\"message\":{\"content\":{\"text\":\"He\"}}}}\n\n"
						+ "data: {\"type\":\"content-delta\",\"delta\":{\"message\":{\"content\":{\"text\":\"llo\"}}}}\n\n"
						+ "data: {\"type\":\"message-end\"}\n\n";
					respond(exchange, 200, sse, "text/event-stream");
				} else {
					String body = "{\"id\":\"chat-1\",\"message\":{\"role\":\"assistant\","
						+ "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]},"
						+ "\"usage\":{\"tokens\":{\"input_tokens\":3,\"output_tokens\":5}}}";
					respond(exchange, 200, body, "application/json");
				}
				return;
			}
			if (path.equals("/embed")) {
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.lastEmbedBody.set(new String(in, StandardCharsets.UTF_8));
				String body = "{\"id\":\"embed-1\",\"embeddings\":{\"float\":[[0.1,0.2]]},"
					+ "\"usage\":{\"tokens\":{\"input_tokens\":4}}}";
				respond(exchange, 200, body, "application/json");
				return;
			}
			respond(exchange, 404, "{}", "application/json");
		});
	}

	/** 发送响应。 */
	private static void respond(HttpExchange ex, int status, String body, String contentType) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 构造指向 mock 的客户端。 */
	private CohereClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new CohereClient(cfg);
	}

	/** 非流式 chat：解析 message.content[].text 与 usage.tokens。 */
	@Test
	public void testChatSuccess() {
		CohereClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(CohereModels.COMMAND_R_PLUS)
			.messages(ChatMessage.user("hi")).build());
		assertEquals("hello", resp.firstText());
		assertEquals("chat-1", resp.id());
		assertNotNull(resp.usage());
		assertEquals(3, resp.usage().promptTokens());
		assertEquals(5, resp.usage().completionTokens());
		assertEquals(8, resp.usage().totalTokens());
		String body = this.lastChatBody.get();
		assertTrue(body.contains("\"model\":\"command-r-plus\""));
		assertTrue(body.contains("\"role\":\"user\""));
		assertTrue(body.contains("\"content\":\"hi\""));
		assertTrue(body.contains("\"stream\":false"));
		assertEquals("Bearer " + API_KEY, this.authHeader.get());
		client.close();
	}

	/** 流式 chat：content-delta 累加增量，message-end 结束，无 [DONE]。 */
	@Test
	public void testChatStream() {
		CohereClient client = newClient();
		StringBuilder sb = new StringBuilder();
		String[] finish = new String[1];
		client.chatStream(ChatRequest.builder().model(CohereModels.COMMAND_R)
			.messages(ChatMessage.user("hi")).build(), (ChatStreamChunk chunk) -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
				if (chunk.finishReason() != null) {
					finish[0] = chunk.finishReason();
				}
			});
		assertEquals("Hello", sb.toString());
		assertEquals("stop", finish[0]);
		assertTrue(this.lastChatBody.get().contains("\"stream\":true"));
		client.close();
	}

	/** 向量：请求含 input_type 与 embedding_types，解析 embeddings.float[0]。 */
	@Test
	public void testEmbedding() {
		CohereClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest(CohereModels.EMBED_V4, List.of("hi")));
		assertEquals(1, resp.embeddings().size());
		assertEquals(2, resp.embeddings().get(0).length, 0);
		assertEquals(0.1f, resp.embeddings().get(0)[0], 0.0001);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 0.0001);
		String body = this.lastEmbedBody.get();
		assertTrue(body.contains("\"model\":\"embed-v4.0\""));
		assertTrue(body.contains("\"input_type\":\"search_document\""));
		assertTrue(body.contains("\"texts\":[\"hi\"]"));
		assertTrue(body.contains("\"embedding_types\":[\"float\"]"));
		client.close();
	}

	/** listModels：Cohere v2 无模型列表 API，抛 AiException。 */
	@Test
	public void testListModelsThrows() {
		CohereClient client = newClient();
		AiException e = assertThrows(AiException.class, client::listModels);
		assertTrue(e.getMessage().contains("Cohere v2 does not provide a models list API"));
		client.close();
	}
}
