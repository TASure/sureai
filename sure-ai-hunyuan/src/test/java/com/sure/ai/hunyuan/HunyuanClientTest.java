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

package com.sure.ai.hunyuan;

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
 * {@link HunyuanClient} 与 {@link HunyuanUtil} 集成测试：本地 HttpServer mock（零真实网络）。
 *
 * <p>官方文档：https://cloud.tencent.com/document/product/1729/111007</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class HunyuanClientTest {

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
		Field f = HunyuanUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		((SingletonHolder<?>) f.get(null)).reset();
	}

	/** 构造客户端。 */
	private HunyuanClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("hy-key").baseUrl(this.baseUrl).build();
		return new HunyuanClient(cfg);
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
		handle(200, "{\"id\":\"hy1\",\"model\":\"hunyuan-turbos-latest\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		HunyuanClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(HunyuanModels.HUNYUAN_TURBOS_LATEST)
			.messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer hy-key", this.lastAuth.get());
		assertTrue(this.lastUri.get().endsWith("/chat/completions"));
		assertTrue(this.lastBody.get().contains("\"model\":\"hunyuan-turbos-latest\""));
		assertEquals("ok", resp.firstText());
		assertEquals(8, resp.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"hy\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"A\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"hy\",\"choices\":[{\"delta\":{\"content\":\"B\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"hy\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		HunyuanClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model(HunyuanModels.HUNYUAN_T1_LATEST)
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
		handle(200, "{\"object\":\"list\",\"model\":\"hunyuan-embedding\",\"data\":["
			+ "{\"index\":0,\"embedding\":[0.1,0.2,0.3],\"object\":\"embedding\"}],"
			+ "\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}");
		HunyuanClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest(
			HunyuanModels.HUNYUAN_EMBEDDING, List.of("你好")));
		assertTrue(this.lastUri.get().endsWith("/embeddings"));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 1e-6);
		client.close();
	}

	/** image 未声明支持，由基类 guard 快速失败抛 AiException。 */
	@Test
	public void testImageUnsupported() {
		HunyuanClient client = newClient();
		AiException ex = assertThrows(AiException.class,
			() -> client.generate(ImageRequest.of("test-image", "a cat")));
		assertTrue(ex.getMessage().contains("hunyuan"));
		assertTrue(ex.getMessage().contains("IMAGE"));
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		HunyuanClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("hunyuan-turbos-latest").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** baseUrl 为空时使用默认地址（仅构造）。 */
	@Test
	public void testDefaultBaseUrlWhenNull() {
		HunyuanClient client = new HunyuanClient(AiConfig.of("k"));
		assertEquals("hunyuan", client.name());
		assertEquals("https://api.hunyuan.cloud.tencent.com/v1", HunyuanClient.DEFAULT_BASE_URL);
		client.close();
	}

	/** Util 便捷 chat。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		HunyuanUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", HunyuanUtil.chat("hunyuan-turbos-latest", "hi").firstText());
		assertEquals("Bearer util-key", this.lastAuth.get());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, HunyuanUtil::client);
	}

	/** buildConfig 分支。 */
	@Test
	public void testBuildConfig() {
		assertEquals("http://mock", HunyuanUtil.buildConfig("k", "http://mock").baseUrl());
		assertEquals(null, HunyuanUtil.buildConfig("k", " ").baseUrl());
		assertThrows(AiException.class, HunyuanUtil::buildConfigFromEnv);
	}

	/** Models 私有构造器与常量值。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<HunyuanModels> c = HunyuanModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("hunyuan-turbos-latest", HunyuanModels.HUNYUAN_TURBOS_LATEST);
		assertEquals("hunyuan-embedding", HunyuanModels.HUNYUAN_EMBEDDING);
	}
}
