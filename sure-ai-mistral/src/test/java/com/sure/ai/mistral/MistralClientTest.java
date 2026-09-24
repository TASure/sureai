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

package com.sure.ai.mistral;

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
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.Model;

/**
 * {@link MistralClient} 与 {@link MistralUtil} 集成测试：本地 HttpServer mock，零真实网络。
 *
 * <p>官方文档：https://docs.mistral.ai/</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public class MistralClientTest {

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
		MistralUtil.resetClient();
	}

	/** 停止服务。 */
	@After
	public void tearDown() throws Exception {
		this.server.stop(0);
		MistralUtil.resetClient();
	}

	/** 构造客户端。 */
	private MistralClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("mis-key").baseUrl(this.baseUrl).build();
		return new MistralClient(cfg);
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
		handle(200, "{\"id\":\"mis-resp\",\"model\":\"mistral-large-latest\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hi there\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3,\"total_tokens\":7}}");
		MistralClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(MistralModels.MISTRAL_LARGE_LATEST)
			.messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer mis-key", this.lastAuth.get());
		assertTrue(this.lastUri.get().endsWith("/v1/chat/completions"));
		assertTrue(this.lastBody.get().contains("\"model\":\"mistral-large-latest\""));
		assertEquals("hi there", resp.firstText());
		assertEquals(7, resp.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"m\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"A\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"m\",\"choices\":[{\"delta\":{\"content\":\"B\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"m\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		MistralClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model(MistralModels.CODESTRAL_LATEST)
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
			+ "{\"id\":\"mistral-large-latest\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"mistralai\"},"
			+ "{\"id\":\"mistral-embed\",\"object\":\"model\",\"created\":1700000001,\"owned_by\":\"mistralai\"}]}");
		MistralClient client = newClient();
		List<Model> models = client.listModels();
		assertTrue(this.lastUri.get().endsWith("/v1/models"));
		assertEquals(2, models.size());
		assertEquals("mistral-large-latest", models.get(0).id());
		assertEquals("mistral-embed", models.get(1).id());
		client.close();
	}

	/** POST /v1/embeddings 向量解析。 */
	@Test
	public void testEmbedding() {
		handle(200, "{\"model\":\"mistral-embed\",\"data\":["
			+ "{\"object\":\"embedding\",\"embedding\":[0.1,0.2,0.3],\"index\":0}],"
			+ "\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}");
		MistralClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest(MistralModels.MISTRAL_EMBED, List.of("hello")));
		assertTrue(this.lastUri.get().endsWith("/v1/embeddings"));
		assertTrue(this.lastBody.get().contains("mistral-embed"));
		assertEquals("mistral-embed", resp.model());
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length);
		assertEquals(0.1f, resp.embeddings().get(0)[0], 1e-6);
		assertEquals(3, resp.usage().totalTokens());
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		MistralClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("mistral-small-latest").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** baseUrl 为空时使用默认地址（仅构造）。 */
	@Test
	public void testDefaultBaseUrlWhenNull() {
		MistralClient client = new MistralClient(AiConfig.of("k"));
		assertEquals("mistral", client.name());
		client.close();
	}

	/** Util 便捷 chat 与 embed。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		MistralUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", MistralUtil.chat("mistral-small-latest", "hi").firstText());
		assertEquals("Bearer util-key", this.lastAuth.get());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, MistralUtil::client);
	}

	/** buildConfig 分支。 */
	@Test
	public void testBuildConfig() {
		assertEquals("http://mock", MistralUtil.buildConfig("k", "http://mock").baseUrl());
		assertEquals(null, MistralUtil.buildConfig("k", " ").baseUrl());
		assertThrows(AiException.class, MistralUtil::buildConfigFromEnv);
	}

	/** Models 私有构造器。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<MistralModels> c = MistralModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("mistral-large-latest", MistralModels.MISTRAL_LARGE_LATEST);
		assertEquals("mistral-embed", MistralModels.MISTRAL_EMBED);
	}
}
