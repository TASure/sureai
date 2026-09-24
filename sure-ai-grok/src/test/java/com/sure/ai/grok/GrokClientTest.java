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

package com.sure.ai.grok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.Model;

/**
 * {@link GrokClient} 与 {@link GrokUtil} 集成测试：本地 HttpServer mock，零真实网络。
 *
 * <p>官方文档：https://docs.x.ai/</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public class GrokClientTest {

	private HttpServer server;
	private String baseUrl;
	private final java.util.concurrent.atomic.AtomicReference<String> lastUri =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastAuth =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastBody =
		new java.util.concurrent.atomic.AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1";
		GrokUtil.resetClient();
	}

	/** 停止服务。 */
	@After
	public void tearDown() throws Exception {
		this.server.stop(0);
		GrokUtil.resetClient();
	}

	/** 构造客户端。 */
	private GrokClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("grok-key").baseUrl(this.baseUrl).build();
		return new GrokClient(cfg);
	}

	/** 注册 JSON 处理器。 */
	private void handle(int status, String responseBody) {
		handle(exchange -> respond(exchange, status, responseBody));
	}

	/** 注册自定义处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.lastUri.set(exchange.getRequestURI().toString());
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

	/** baseUrl 拼接：/v1/chat/completions，Bearer 头，响应解析。 */
	@Test
	public void testChatSuccess() {
		handle(200, "{\"id\":\"grok-resp\",\"model\":\"grok-4.6\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":4,\"total_tokens\":10}}");
		GrokClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(GrokModels.GROK_4_6)
			.messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer grok-key", this.lastAuth.get());
		assertTrue(this.lastUri.get().endsWith("/v1/chat/completions"));
		assertTrue(this.lastBody.get().contains("\"model\":\"grok-4.6\""));
		assertEquals("hello", resp.firstText());
		assertEquals(10, resp.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"g\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"A\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"g\",\"choices\":[{\"delta\":{\"content\":\"B\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"g\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		GrokClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model(GrokModels.GROK_3)
			.messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("AB", sb.toString());
		client.close();
	}

	/** GET /v1/models 列表解析。 */
	@Test
	public void testListModels() {
		handle(200, "{\"object\":\"list\",\"data\":["
			+ "{\"id\":\"grok-3\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"xai\"},"
			+ "{\"id\":\"grok-3-mini\",\"object\":\"model\",\"created\":1700000001,\"owned_by\":\"xai\"}]}");
		GrokClient client = newClient();
		List<Model> models = client.listModels();
		assertTrue(this.lastUri.get().endsWith("/v1/models"));
		assertEquals(2, models.size());
		assertEquals("grok-3", models.get(0).id());
		assertEquals("grok-3-mini", models.get(1).id());
		client.close();
	}

	/** xAI 不提供 embeddings，抛 AiException。 */
	@Test
	public void testEmbeddingThrows() {
		GrokClient client = newClient();
		assertThrows(AiException.class,
			() -> client.embed(new EmbeddingRequest("grok-3", List.of("x"))));
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		GrokClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("grok-3").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** baseUrl 为空时使用默认地址（仅构造）。 */
	@Test
	public void testDefaultBaseUrlWhenNull() {
		GrokClient client = new GrokClient(AiConfig.of("k"));
		assertEquals("grok", client.name());
		client.close();
	}

	/** Util 便捷 chat。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		GrokUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", GrokUtil.chat("grok-3", "hi").firstText());
		assertEquals("Bearer util-key", this.lastAuth.get());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, GrokUtil::client);
	}

	/** buildConfig 分支。 */
	@Test
	public void testBuildConfig() {
		assertEquals("http://mock", GrokUtil.buildConfig("k", "http://mock").baseUrl());
		assertEquals(null, GrokUtil.buildConfig("k", " ").baseUrl());
		assertThrows(AiException.class, GrokUtil::buildConfigFromEnv);
	}

	/** Models 私有构造器。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<GrokModels> c = GrokModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("grok-4.6", GrokModels.GROK_4_6);
		assertEquals("grok-code-fast-1", GrokModels.GROK_CODE_FAST_1);
	}
}
