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

package com.sure.ai.stepfun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
 * {@link StepFunClient} 与 {@link StepFunUtil} 集成测试：本地 HttpServer mock（零真实网络）。
 *
 * <p>官方文档：https://platform.stepfun.com/docs/zh/api-reference/chat/chat-completion-create</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class StepFunClientTest {

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
		Field f = StepFunUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		((SingletonHolder<?>) f.get(null)).reset();
	}

	/** 构造客户端。 */
	private StepFunClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("sf-key").baseUrl(this.baseUrl).build();
		return new StepFunClient(cfg);
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
		handle(200, "{\"id\":\"sf1\",\"model\":\"step-5-preview\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		StepFunClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(StepFunModels.STEP_5_PREVIEW)
			.messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer sf-key", this.lastAuth.get());
		assertTrue(this.lastUri.get().endsWith("/chat/completions"));
		assertTrue(this.lastBody.get().contains("\"model\":\"step-5-preview\""));
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
		StepFunClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model(StepFunModels.STEP_2_MINI)
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
		StepFunClient client = newClient();
		AiException ex = assertThrows(AiException.class,
			() -> client.embed(new EmbeddingRequest("m", java.util.List.of("x"))));
		assertTrue(ex.getMessage().contains("stepfun"));
		assertTrue(ex.getMessage().contains("EMBED"));
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		StepFunClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("step-5-preview").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** baseUrl 为空时使用默认地址（仅构造）。 */
	@Test
	public void testDefaultBaseUrlWhenNull() {
		StepFunClient client = new StepFunClient(AiConfig.of("k"));
		assertEquals("stepfun", client.name());
		assertEquals("https://api.stepfun.com/v1", StepFunClient.DEFAULT_BASE_URL);
		client.close();
	}

	/** Util 便捷 chat。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		StepFunUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", StepFunUtil.chat("step-2-16k", "hi").firstText());
		assertEquals("Bearer util-key", this.lastAuth.get());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, StepFunUtil::client);
	}

	/** buildConfig 分支。 */
	@Test
	public void testBuildConfig() {
		assertEquals("http://mock", StepFunUtil.buildConfig("k", "http://mock").baseUrl());
		assertEquals(null, StepFunUtil.buildConfig("k", " ").baseUrl());
		assertThrows(AiException.class, StepFunUtil::buildConfigFromEnv);
	}

	/** Models 私有构造器与常量值。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<StepFunModels> c = StepFunModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("step-2-16k", StepFunModels.STEP_2_16K);
	}

	/** Util 私有构造器。 */
	@Test
	public void testUtilPrivateConstructor() throws Exception {
		java.lang.reflect.Constructor<StepFunUtil> c = StepFunUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
	}

	/** init(String apiKey) 显式初始化。 */
	@Test
	public void testInitWithApiKey() {
		StepFunUtil.init("plain-key");
		assertEquals("stepfun", StepFunUtil.client().name());
	}

	/** Util chat(ChatRequest) 便捷方法。 */
	@Test
	public void testUtilChatWithRequest() {
		handle(200, "{\"id\":\"u2\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"req-resp\"},\"finish_reason\":\"stop\"}]}");
		StepFunUtil.init(AiConfig.builder().apiKey("util-key2").baseUrl(this.baseUrl).build());
		ChatResponse resp = StepFunUtil.chat(ChatRequest.builder()
			.model(StepFunModels.STEP_2_16K)
			.messages(ChatMessage.user("hello")).build());
		assertEquals("req-resp", resp.firstText());
		assertEquals("Bearer util-key2", this.lastAuth.get());
	}

	/** Util chatStream 便捷方法。 */
	@Test
	public void testUtilChatStream() {
		String sse = "data: {\"id\":\"s\",\"choices\":[{\"delta\":{\"content\":\"X\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"s\",\"choices\":[{\"delta\":{\"content\":\"Y\"},\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		StepFunUtil.init(AiConfig.builder().apiKey("stream-key").baseUrl(this.baseUrl).build());
		StringBuilder sb = new StringBuilder();
		StepFunUtil.chatStream(ChatRequest.builder()
			.model(StepFunModels.STEP_2_16K)
			.messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("XY", sb.toString());
	}

	/** 从环境变量懒加载成功路径。 */
	@Test
	public void testLazyLoadFromEnv() throws Exception {
		setEnv(StepFunUtil.ENV_API_KEY, "env-key-123");
		try {
			resetUtil();
			assertEquals("stepfun", StepFunUtil.client().name());
		} finally {
			removeEnv(StepFunUtil.ENV_API_KEY);
			resetUtil();
		}
	}

	/** 反射写入环境变量。 */
	@SuppressWarnings("unchecked")
	private static void setEnv(String key, String value) throws Exception {
		Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
		Field f = pe.getDeclaredField("theUnmodifiableEnvironment");
		f.setAccessible(true);
		Map<String, String> unmod = (Map<String, String>) f.get(null);
		Field m = unmod.getClass().getDeclaredField("m");
		m.setAccessible(true);
		Map<String, String> inner = (Map<String, String>) m.get(unmod);
		inner.put(key, value);
	}

	/** 反射删除环境变量。 */
	@SuppressWarnings("unchecked")
	private static void removeEnv(String key) throws Exception {
		Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
		Field f = pe.getDeclaredField("theUnmodifiableEnvironment");
		f.setAccessible(true);
		Map<String, String> unmod = (Map<String, String>) f.get(null);
		Field m = unmod.getClass().getDeclaredField("m");
		m.setAccessible(true);
		Map<String, String> inner = (Map<String, String>) m.get(unmod);
		inner.remove(key);
	}
}
