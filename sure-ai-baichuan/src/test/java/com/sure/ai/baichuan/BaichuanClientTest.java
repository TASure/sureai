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

package com.sure.ai.baichuan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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

/**
 * {@link BaichuanClient} 与 {@link BaichuanUtil} 集成测试：本地 HttpServer mock（零真实网络）。
 *
 * <p>官方文档：https://platform.baichuan-ai.com/docs/api</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class BaichuanClientTest {

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
		Field f = BaichuanUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		((SingletonHolder<?>) f.get(null)).reset();
	}

	/** 构造客户端。 */
	private BaichuanClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("bc-key").baseUrl(this.baseUrl).build();
		return new BaichuanClient(cfg);
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
		handle(200, "{\"id\":\"bc1\",\"model\":\"Baichuan4-Turbo\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		BaichuanClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(BaichuanModels.BAICHUAN4_TURBO)
			.messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer bc-key", this.lastAuth.get());
		assertTrue(this.lastUri.get().endsWith("/chat/completions"));
		assertTrue(this.lastBody.get().contains("\"model\":\"Baichuan4-Turbo\""));
		assertEquals("ok", resp.firstText());
		assertEquals(8, resp.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"bc\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"A\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"bc\",\"choices\":[{\"delta\":{\"content\":\"B\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"bc\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		BaichuanClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model(BaichuanModels.BAICHUAN3_TURBO)
			.messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("AB", sb.toString());
		client.close();
	}

	/** embeddings 未声明支持，由基类 guard 快速失败抛 AiException（1.4.0 P2-6 统一消息）。 */
	@Test
	public void testEmbedUnsupported() {
		BaichuanClient client = newClient();
		AiException ex = assertThrows(AiException.class,
			() -> client.embed(new EmbeddingRequest("m", java.util.List.of("x"))));
		assertTrue(ex.getMessage().contains("baichuan"));
		assertTrue(ex.getMessage().contains("EMBED"));
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		BaichuanClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("Baichuan4-Turbo").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** baseUrl 为空时使用默认地址（仅构造）。 */
	@Test
	public void testDefaultBaseUrlWhenNull() {
		BaichuanClient client = new BaichuanClient(AiConfig.of("k"));
		assertEquals("baichuan", client.name());
		assertEquals("https://api.baichuan-ai.com/v1", BaichuanClient.DEFAULT_BASE_URL);
		client.close();
	}

	/** Util 便捷 chat。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		BaichuanUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", BaichuanUtil.chat("Baichuan4", "hi").firstText());
		assertEquals("Bearer util-key", this.lastAuth.get());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, BaichuanUtil::client);
	}

	/** buildConfig 分支。 */
	@Test
	public void testBuildConfig() {
		assertEquals("http://mock", BaichuanUtil.buildConfig("k", "http://mock").baseUrl());
		assertEquals(null, BaichuanUtil.buildConfig("k", " ").baseUrl());
		assertThrows(AiException.class, BaichuanUtil::buildConfigFromEnv);
	}

	/** Models 私有构造器与常量值。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<BaichuanModels> c = BaichuanModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("Baichuan4", BaichuanModels.BAICHUAN4);
	}
}
