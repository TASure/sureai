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

package com.sure.ai.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
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
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.Model;
import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;

/**
 * {@link AzureClient} 与 {@link AzureUtil} 集成测试：本地 HttpServer mock。
 *
 * <p>官方文档：https://learn.microsoft.com/en-us/azure/ai-foundry/openai/reference</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class AzureClientTest {

	private HttpServer server;
	private String baseUrl;
	private final java.util.concurrent.atomic.AtomicReference<String> lastUri =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastApiKey =
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
		this.baseUrl = "http://127.0.0.1:" + port;
		resetUtil();
	}

	/** 停止服务。 */
	@After
	public void tearDown() throws Exception {
		this.server.stop(0);
		resetUtil();
	}

	/** 反射清空静态单例。 */
	private static void resetUtil() throws Exception {
		Field f = AzureUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		((SingletonHolder<?>) f.get(null)).reset();
	}

	/** 构造客户端（deployment/api-version 经 extraHeaders 传入）。 */
	private AzureClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("azure-key").baseUrl(this.baseUrl)
			.extraHeader("deployment", "my-gpt4o").extraHeader("api-version", "2024-10-21").build();
		return new AzureClient(cfg);
	}

	/** 注册 JSON 处理器。 */
	private void handle(int status, String responseBody) {
		handle(exchange -> respond(exchange, status, responseBody));
	}

	/** 注册自定义处理器，记录请求 URI/头/体。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.lastUri.set(exchange.getRequestURI().toString());
			this.lastApiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
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

	/** api-key 头、URL 含 deployment 与 api-version、chat 响应解析。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"az1\",\"model\":\"my-gpt4o\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		AzureClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("my-gpt4o")
			.messages(ChatMessage.user("hi")).build());
		assertEquals("azure-key", this.lastApiKey.get());
		assertNull("Azure 不应使用 Authorization Bearer 头", this.lastAuth.get());
		assertTrue(this.lastUri.get().contains("/openai/deployments/my-gpt4o/chat/completions"));
		assertTrue(this.lastUri.get().contains("api-version=2024-10-21"));
		assertEquals("hi", resp.firstText());
		assertEquals(8, resp.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"az\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"He\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"az\",\"choices\":[{\"delta\":{\"content\":\"llo\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"az\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		AzureClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model("my-gpt4o").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("Hello", sb.toString());
		client.close();
	}

	/** embeddings：URL 含 /embeddings。 */
	@Test
	public void testEmbeddings() {
		handle(200, "{\"model\":\"emb\",\"data\":[{\"embedding\":[0.1,0.2]}],"
			+ "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":0,\"total_tokens\":2}}");
		AzureClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest("emb", List.of("hi")));
		assertTrue(this.lastUri.get().contains("/openai/deployments/my-gpt4o/embeddings"));
		assertTrue(this.lastUri.get().contains("api-version=2024-10-21"));
		assertEquals(2, resp.embeddings().get(0).length, 0);
		client.close();
	}

	/** 图像生成：URL 含 deployment/images/generations/api-version，响应解析正确。 */
	@Test
	public void testImageGeneration() {
		handle(200, "{\"created\":1700000000,\"data\":["
			+ "{\"url\":\"https://example.com/azure-img.png\",\"revised_prompt\":\"a flower\"}]}");
		AzureClient client = newClient();
		ImageResponse resp = client.generate(ImageRequest.builder()
			.model("my-dalle3").prompt("a flower").build());
		assertTrue(this.lastUri.get().contains("/openai/deployments/my-gpt4o/images/generations"));
		assertTrue(this.lastUri.get().contains("api-version=2024-10-21"));
		assertEquals("azure-key", this.lastApiKey.get());
		assertNull("Azure 不应使用 Authorization Bearer 头", this.lastAuth.get());
		assertEquals("https://example.com/azure-img.png", resp.firstUrl());
		assertEquals(1700000000L, resp.created());
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		AzureClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("my-gpt4o").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 缺省 deployment/apiVersion 与 resource 推导 baseUrl（仅构造，不发起网络）。 */
	@Test
	public void testDefaults() {
		AiConfig cfg = AiConfig.builder().apiKey("k").extraHeader("resource", "myres").build();
		AzureClient client = new AzureClient(cfg);
		assertEquals("azure", client.name());
		assertEquals(AzureClient.DEFAULT_DEPLOYMENT, client.deployment());
		assertEquals(AzureClient.DEFAULT_API_VERSION, client.apiVersion());
		client.close();
	}

	/** Util 显式 init 后便捷 chat。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		AiConfig cfg = AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl)
			.extraHeader("deployment", "dep1").build();
		AzureUtil.init(cfg);
		assertEquals("yo", AzureUtil.chat("dep1", "hi").firstText());
		assertEquals("util-key", this.lastApiKey.get());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, AzureUtil::client);
	}

	/** buildConfig 分支覆盖。 */
	@Test
	public void testBuildConfig() {
		AiConfig withUrl = AzureUtil.buildConfig("k", "http://mock", "res");
		assertEquals("http://mock", withUrl.baseUrl());
		AiConfig withResource = AzureUtil.buildConfig("k", null, "myres");
		assertEquals("myres", withResource.extraHeaders().get("resource"));
		assertThrows(AiException.class, AzureUtil::buildConfigFromEnv);
	}

	/** 模型列表：GET /openai/models?api-version=...，api-key 头，解析 data[]。 */
	@Test
	public void testListModels() {
		handle(200, "{\"object\":\"list\",\"data\":["
			+ "{\"id\":\"gpt-4o\",\"created\":1700000000,\"owned_by\":\"azure\"},"
			+ "{\"id\":\"gpt-4o-mini\",\"created\":1700000001,\"owned_by\":\"azure\"}]}");
		AzureClient client = newClient();
		List<Model> models = client.listModels();
		assertEquals("azure-key", this.lastApiKey.get());
		assertNull("Azure 不应使用 Authorization Bearer 头", this.lastAuth.get());
		assertTrue(this.lastUri.get().contains("/openai/models"));
		assertTrue(this.lastUri.get().contains("api-version=2024-10-21"));
		assertEquals(2, models.size());
		assertEquals("gpt-4o", models.get(0).id());
		assertEquals("azure", models.get(0).ownedBy());
		client.close();
	}

	/** 内容审核：POST /openai/moderations?api-version=...，api-key 头，解析 results。 */
	@Test
	public void testModeration() {
		handle(200, "{\"id\":\"mod-1\",\"model\":\"text-moderation-latest\",\"results\":["
			+ "{\"flagged\":false,\"categories\":{},\"category_scores\":{}}]}");
		AzureClient client = newClient();
		ModerationResponse resp = client.moderate(ModerationRequest.of("hi"));
		assertEquals("azure-key", this.lastApiKey.get());
		assertNull("Azure 不应使用 Authorization Bearer 头", this.lastAuth.get());
		assertTrue(this.lastUri.get().contains("/openai/moderations"));
		assertTrue(this.lastUri.get().contains("api-version=2024-10-21"));
		assertEquals("mod-1", resp.id());
		assertEquals(1, resp.results().size());
		assertFalse(resp.flagged());
		client.close();
	}

	/** 微调：createFineTune POST /openai/fine_tuning/jobs、getFineTune GET 正确拼接 api-version、uploadTrainingFile。 */
	@Test
	public void testFineTune() {
		// 同一测试内顺序发起三类请求，用单一 handler 按 path 路由响应（"/" 上下文仅能注册一次）。
		handle(ex -> {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			String body;
			if ("GET".equals(method) && path.endsWith("/fine_tuning/jobs/ft-1")) {
				body = "{\"id\":\"ft-1\",\"status\":\"succeeded\",\"fine_tuned_model\":\"gpt-4o.ft\"}";
			} else if ("POST".equals(method) && path.endsWith("/files")) {
				body = "{\"id\":\"file-9\",\"filename\":\"train.jsonl\"}";
			} else {
				body = "{\"id\":\"ft-1\",\"status\":\"queued\",\"model\":\"gpt-4o\"}";
			}
			respond(ex, 200, body);
		});
		AzureClient client = newClient();
		FineTuneRequest req = FineTuneRequest.builder().model("gpt-4o").trainingFileId("file-1").build();
		FineTuneResponse created = client.createFineTune(req);
		assertEquals("ft-1", created.id());
		assertEquals("queued", created.status());
		assertTrue(this.lastUri.get().contains("/openai/fine_tuning/jobs"));
		assertTrue(this.lastUri.get().contains("api-version=2024-10-21"));
		assertEquals("azure-key", this.lastApiKey.get());
		assertNull("Azure 不应使用 Authorization Bearer 头", this.lastAuth.get());

		FineTuneResponse got = client.getFineTune("ft-1");
		assertEquals("ft-1", got.id());
		assertEquals("succeeded", got.status());
		assertEquals("gpt-4o.ft", got.fineTunedModel());
		assertTrue(this.lastUri.get(), this.lastUri.get().contains("/openai/fine_tuning/jobs/ft-1"));
		assertTrue(this.lastUri.get().contains("api-version=2024-10-21"));

		String fileId = client.uploadTrainingFile("train.jsonl", "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
		assertEquals("file-9", fileId);
		assertTrue(this.lastUri.get().contains("/openai/files"));
		assertTrue(this.lastUri.get().contains("api-version=2024-10-21"));
		client.close();
	}

	/** Models 私有构造器。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<AzureModels> c = AzureModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("gpt-4o", AzureModels.GPT_4O);
	}
}
