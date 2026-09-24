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

package com.sure.ai.llamacpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
import com.sure.ai.model.Model;

/**
 * {@link LlamaCppClient} 与 {@link LlamaCppUtil} 集成测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.2.0
 */
public class LlamaCppClientTest {

	private HttpServer server;
	private String baseUrl;
	private final java.util.concurrent.atomic.AtomicReference<String> lastBody =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastAuth =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastPath =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastMethod =
		new java.util.concurrent.atomic.AtomicReference<>();

	/** 启动本地服务并重置静态单例。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1";
		this.lastBody.set(null);
		this.lastAuth.set(null);
		this.lastPath.set(null);
		this.lastMethod.set(null);
		resetUtil();
	}

	/** 停止服务并重置静态单例。 */
	@After
	public void tearDown() throws Exception {
		this.server.stop(0);
		resetUtil();
	}

	/** 反射清空 LlamaCppUtil 静态单例。 */
	private static void resetUtil() throws Exception {
		Field f = LlamaCppUtil.class.getDeclaredField("client");
		f.setAccessible(true);
		f.set(null, null);
	}

	/** 构造客户端（baseUrl 指向本地 mock，默认 dummy key 不发送鉴权头）。 */
	private LlamaCppClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(LlamaCppClient.DEFAULT_API_KEY).baseUrl(this.baseUrl).build();
		return new LlamaCppClient(cfg);
	}

	/** 注册默认 JSON 处理器。 */
	private void handle(int status, String responseBody) {
		handle(ex -> respond(ex, status, responseBody));
	}

	/** 注册自定义处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.lastMethod.set(exchange.getRequestMethod());
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
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

	/** 非流式对话：POST /v1/chat/completions，OpenAI 格式请求与响应解析。 */
	@Test
	public void testChatSuccess() {
		handle(200, "{\"id\":\"chatcmpl-1\",\"model\":\"llama-3.1-8b\","
			+ "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"The sky is blue.\"},"
			+ "\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":8,\"total_tokens\":23}}");
		LlamaCppClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(LlamaCppModels.LLAMA_3_1_8B)
			.messages(ChatMessage.user("why is the sky blue")).temperature(0.7).build());
		assertEquals("POST", this.lastMethod.get());
		assertEquals("/v1/chat/completions", this.lastPath.get());
		assertNull("dummy key 不发送鉴权头", this.lastAuth.get());
		assertTrue(this.lastBody.get().contains("\"model\":\"llama-3.1-8b\""));
		assertTrue(this.lastBody.get().contains("\"temperature\":0.7"));
		assertEquals("llama-3.1-8b", resp.model());
		assertEquals("The sky is blue.", resp.firstText());
		assertEquals("stop", resp.choices().get(0).finishReason());
		assertEquals(23, resp.usage().totalTokens());
		client.close();
	}

	/** SSE 流式对话：data: 分片聚合 + [DONE] 终止。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"s1\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"s1\",\"choices\":[{\"delta\":{\"content\":\"lo\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"s1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		LlamaCppClient client = newClient();
		StringBuilder sb = new StringBuilder();
		String[] finish = new String[1];
		client.chatStream(ChatRequest.builder().model(LlamaCppModels.QWEN2_5_7B)
			.messages(ChatMessage.user("hi")).build(), chunk -> {
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

	/** 向量：POST /v1/embeddings，OpenAI 格式 data[].embedding 解析。 */
	@Test
	public void testEmbedding() {
		handle(200, "{\"model\":\"bge-m3\",\"data\":[{\"embedding\":[0.1,0.2,0.3]}],"
			+ "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":0,\"total_tokens\":2}}");
		LlamaCppClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest(LlamaCppModels.BGE_M3, List.of("hello")));
		assertEquals("POST", this.lastMethod.get());
		assertEquals("/v1/embeddings", this.lastPath.get());
		assertTrue(this.lastBody.get().contains("\"model\":\"bge-m3\""));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 1e-6);
		client.close();
	}

	/** 模型列表：GET /v1/models，解析 data[].id。 */
	@Test
	public void testListModels() {
		handle(200, "{\"object\":\"list\",\"data\":["
			+ "{\"id\":\"llama-3.1-8b\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"llama.cpp\"},"
			+ "{\"id\":\"bge-m3\",\"object\":\"model\",\"created\":1700000001,\"owned_by\":\"llama.cpp\"}"
			+ "]}");
		LlamaCppClient client = newClient();
		List<Model> models = client.listModels();
		assertEquals("GET", this.lastMethod.get());
		assertEquals("/v1/models", this.lastPath.get());
		assertEquals(2, models.size());
		assertEquals("llama-3.1-8b", models.get(0).id());
		assertEquals("bge-m3", models.get(1).id());
		client.close();
	}

	/** LlamaCppUtil 便捷方法 + 真实 API Key 发送 Bearer 头。 */
	@Test
	public void testUtilConvenienceWithBearer() {
		handle(200, "{\"id\":\"u\",\"model\":\"m\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		LlamaCppUtil.init(AiConfig.builder().apiKey("secret-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", LlamaCppUtil.chat("m", "hi").firstText());
		assertEquals("Bearer secret-key", this.lastAuth.get());
		assertEquals("llamacpp", LlamaCppUtil.name());
	}

	/** LlamaCppUtil 便捷方法：chat(ChatRequest)、chatStream、embed、listModels。 */
	@Test
	public void testUtilAllConvenience() {
		handle(ex -> {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			if ("POST".equals(method) && path.endsWith("/chat/completions")) {
				respond(ex, 200, "{\"id\":\"u2\",\"model\":\"m\",\"choices\":[{\"index\":0,"
					+ "\"message\":{\"role\":\"assistant\",\"content\":\"util-ok\"},\"finish_reason\":\"stop\"}]}");
			} else if ("POST".equals(method) && path.endsWith("/embeddings")) {
				respond(ex, 200, "{\"model\":\"bge-m3\",\"data\":[{\"embedding\":[0.5,0.6]}]}");
			} else if ("GET".equals(method) && path.endsWith("/models")) {
				respond(ex, 200, "{\"data\":[{\"id\":\"m1\"}]}");
			} else {
				respond(ex, 404, "{\"error\":\"not found\"}");
			}
		});
		LlamaCppUtil.init(AiConfig.builder().apiKey(LlamaCppClient.DEFAULT_API_KEY).baseUrl(this.baseUrl).build());
		// chat(ChatRequest)
		ChatResponse r = LlamaCppUtil.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("hi")).build());
		assertEquals("util-ok", r.firstText());
		// embed(String, String)
		EmbeddingResponse e = LlamaCppUtil.embed("bge-m3", "hello");
		assertEquals(1, e.embeddings().size());
		assertEquals(2, e.embeddings().get(0).length, 0);
		// embed(EmbeddingRequest)
		EmbeddingResponse e2 = LlamaCppUtil.embed(new EmbeddingRequest("bge-m3", List.of("hi")));
		assertEquals(1, e2.embeddings().size());
		// listModels()
		List<Model> ms = LlamaCppUtil.listModels();
		assertEquals(1, ms.size());
		assertEquals("m1", ms.get(0).id());
	}

	/** LlamaCppUtil.chatStream 便捷方法。 */
	@Test
	public void testUtilChatStream() {
		String sse = "data: {\"id\":\"s\",\"choices\":[{\"delta\":{\"content\":\"abc\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"s\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		LlamaCppUtil.init(AiConfig.builder().apiKey(LlamaCppClient.DEFAULT_API_KEY).baseUrl(this.baseUrl).build());
		StringBuilder sb = new StringBuilder();
		LlamaCppUtil.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("abc", sb.toString());
	}

	/** 未 init 时 client() 走环境变量/默认值分支（仅构造，不发请求）。 */
	@Test
	public void testClientFallbackDefaults() throws Exception {
		resetUtil();
		assertEquals("llamacpp", LlamaCppUtil.client().name());
		LlamaCppUtil.init();
		assertEquals("llamacpp", LlamaCppUtil.name());
	}

	/** baseUrl 为空时使用默认地址；name() 返回 "llamacpp"。 */
	@Test
	public void testDefaultBaseUrlAndName() {
		LlamaCppClient client = new LlamaCppClient(AiConfig.of(LlamaCppClient.DEFAULT_API_KEY));
		assertEquals("llamacpp", client.name());
		client.close();
	}

	/** Models 私有构造器不可实例化。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<LlamaCppModels> c = LlamaCppModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = org.junit.Assert.assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("llama-3.1-8b", LlamaCppModels.LLAMA_3_1_8B);
	}
}
