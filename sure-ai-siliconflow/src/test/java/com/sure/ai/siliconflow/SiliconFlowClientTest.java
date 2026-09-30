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

package com.sure.ai.siliconflow;

import static org.junit.Assert.assertEquals;
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
import com.sure.ai.model.ImageRequest;

/**
 * {@link SiliconFlowClient} 与 {@link SiliconFlowUtil} 集成测试：本地 HttpServer mock（零真实网络）。
 *
 * <p>官方文档：https://docs.siliconflow.cn/cn/userguide/capabilities/text-generation</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class SiliconFlowClientTest {

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
		Field f = SiliconFlowUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		((SingletonHolder<?>) f.get(null)).reset();
	}

	/** 构造客户端。 */
	private SiliconFlowClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("sf-key").baseUrl(this.baseUrl).build();
		return new SiliconFlowClient(cfg);
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

	/** baseUrl 拼接：/chat/completions，Bearer 头，响应解析。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"sf1\",\"model\":\"deepseek-ai/DeepSeek-V3\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		SiliconFlowClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(SiliconFlowModels.DEEPSEEK_V3)
			.messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer sf-key", this.lastAuth.get());
		assertTrue(this.lastUri.get().endsWith("/chat/completions"));
		assertTrue(this.lastBody.get().contains("\"model\":\"deepseek-ai/DeepSeek-V3\""));
		assertEquals("ok", resp.firstText());
		assertEquals(8, resp.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"sf\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"A\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"sf\",\"choices\":[{\"delta\":{\"content\":\"B\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"sf\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		SiliconFlowClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model(SiliconFlowModels.QWEN25_72B_INSTRUCT)
			.messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("AB", sb.toString());
		client.close();
	}

	/** embeddings 已声明支持：命中 /embeddings 路径并解析向量。 */
	@Test
	public void testEmbedSupported() {
		handle(200, "{\"object\":\"list\",\"model\":\"BAAI/bge-m3\",\"data\":["
			+ "{\"index\":0,\"embedding\":[0.1,0.2,0.3],\"object\":\"embedding\"}],"
			+ "\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}");
		SiliconFlowClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest(
			SiliconFlowModels.BGE_M3, List.of("你好")));
		assertTrue(this.lastUri.get().endsWith("/embeddings"));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 1e-6);
		client.close();
	}

	/** image 未声明支持，由基类 guard 快速失败抛 AiException。 */
	@Test
	public void testImageUnsupported() {
		SiliconFlowClient client = newClient();
		AiException ex = assertThrows(AiException.class,
			() -> client.generate(ImageRequest.of("test-image", "a cat")));
		assertTrue(ex.getMessage().contains("siliconflow"));
		assertTrue(ex.getMessage().contains("IMAGE"));
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		SiliconFlowClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("deepseek-ai/DeepSeek-V3").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** baseUrl 为空时使用默认地址（仅构造）。 */
	@Test
	public void testDefaultBaseUrlWhenNull() {
		SiliconFlowClient client = new SiliconFlowClient(AiConfig.of("k"));
		assertEquals("siliconflow", client.name());
		assertEquals("https://api.siliconflow.cn/v1", SiliconFlowClient.DEFAULT_BASE_URL);
		client.close();
	}

	/** Util 便捷 chat。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		SiliconFlowUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", SiliconFlowUtil.chat("deepseek-ai/DeepSeek-V3", "hi").firstText());
		assertEquals("Bearer util-key", this.lastAuth.get());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, SiliconFlowUtil::client);
	}

	/** buildConfig 分支。 */
	@Test
	public void testBuildConfig() {
		assertEquals("http://mock", SiliconFlowUtil.buildConfig("k", "http://mock").baseUrl());
		assertEquals(null, SiliconFlowUtil.buildConfig("k", " ").baseUrl());
		assertThrows(AiException.class, SiliconFlowUtil::buildConfigFromEnv);
	}

	/** Models 私有构造器与常量值。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<SiliconFlowModels> c =
			SiliconFlowModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("deepseek-ai/DeepSeek-V3", SiliconFlowModels.DEEPSEEK_V3);
		assertEquals("BAAI/bge-m3", SiliconFlowModels.BGE_M3);
	}
}
