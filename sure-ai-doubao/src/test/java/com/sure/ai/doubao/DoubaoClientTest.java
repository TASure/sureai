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

package com.sure.ai.doubao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
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
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * {@link DoubaoClient} 与 {@link DoubaoUtil} 测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class DoubaoClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastPath = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/api/v3";
		this.lastAuth.set(null);
		this.lastPath.set(null);
		this.lastBody.set(null);
		setSingleton(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		setSingleton(null);
	}

	/** 反射设置 Util 单例。 */
	private static void setSingleton(DoubaoClient c) {
		try {
			Field f = DoubaoUtil.class.getDeclaredField("client");
			f.setAccessible(true);
			f.set(null, c);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 构造指向 mock 的客户端。 */
	private DoubaoClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("ark-key").baseUrl(this.baseUrl).build();
		return new DoubaoClient(cfg);
	}

	/** 注册处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			h.handle(exchange);
		});
	}

	/** 注册固定 JSON 响应。 */
	private void handle(int status, String body) {
		handle(ex -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
	}

	/** 处理器函数式接口。 */
	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws IOException;
	}

	/** Bearer 头 + 路径为 /api/v3/chat/completions。 */
	@Test
	public void testBearerAndPath() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}");
		DoubaoClient client = newClient();
		client.chat(ChatRequest.builder().model("ep-123456").messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer ark-key", this.lastAuth.get());
		assertTrue(this.lastPath.get().endsWith("/api/v3/chat/completions"));
		client.close();
	}

	/** 正常 chat：model 透传为 ep-xxx。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"c\",\"model\":\"ep-1\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}");
		DoubaoClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("ep-123456")
			.messages(ChatMessage.user("你好")).build());
		assertEquals("你好", resp.firstText());
		assertTrue(this.lastBody.get().contains("\"model\":\"ep-123456\""));
		client.close();
	}

	/** 流式 chat。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"你\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"好\"},\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		DoubaoClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model("ep-1").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("你好", sb.toString());
		client.close();
	}

	/** embeddings。 */
	@Test
	public void testEmbeddings() {
		handle(200, "{\"model\":\"doubao-embedding-text-240715\",\"data\":[{\"embedding\":[0.1,0.2]}],"
			+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"total_tokens\":1}}");
		DoubaoClient client = newClient();
		assertEquals(1, client.embed("doubao-embedding-text-240715", "hi").embeddings().size());
		assertTrue(this.lastPath.get().endsWith("/api/v3/embeddings"));
		client.close();
	}

	/** 401 映射。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"invalid\"}");
		DoubaoClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("ep-1").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 默认 baseUrl 与 name()。 */
	@Test
	public void testDefaultBaseUrl() {
		assertEquals("doubao", newClient().name());
		assertEquals("https://ark.cn-beijing.volces.com/api/v3", DoubaoClient.DEFAULT_BASE_URL);
		assertNotNull(new DoubaoClient(AiConfig.of("k")));
	}

	/** Models 常量。 */
	@Test
	public void testModelsConstants() {
		assertEquals("doubao-1-5-pro-32k", DoubaoModels.DOUBAO_1_5_PRO_32K);
		assertEquals("doubao-embedding-text-240715", DoubaoModels.DOUBAO_EMBEDDING_TEXT);
		List<String> ids = List.of(DoubaoModels.DOUBAO_1_5_PRO_32K, DoubaoModels.DOUBAO_1_5_LITE_32K,
			DoubaoModels.DOUBAO_PRO_32K, DoubaoModels.DOUBAO_PRO_4K, DoubaoModels.DOUBAO_EMBEDDING_TEXT);
		for (String id : ids) {
			assertFalse(id.isEmpty());
		}
	}

	/** Util：init + 便捷方法。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		DoubaoUtil.init(AiConfig.builder().apiKey("ark").baseUrl(this.baseUrl).build());
		assertEquals("ok", DoubaoUtil.chat("ep-1", "hi").firstText());
		assertNotNull(DoubaoUtil.client());
	}

	/** Util：未注入时走环境变量懒加载。 */
	@Test
	public void testUtilLazyFromEnv() {
		setSingleton(null);
		if (System.getenv(DoubaoUtil.ENV_API_KEY) == null) {
			assertThrows(com.sure.ai.exception.AiException.class, DoubaoUtil::client);
		} else {
			assertNotNull(DoubaoUtil.client());
		}
	}

	/** Util：init(String) 不触网。 */
	@Test
	public void testUtilInitByKey() {
		DoubaoUtil.init("ark-key");
		assertNotNull(DoubaoUtil.client());
	}

	/** 方舟模型列表需 IAMS 签名：listModels() 应直接抛 AiException，不发网络请求。 */
	@Test
	public void testListModelsThrows() {
		DoubaoClient client = newClient();
		com.sure.ai.exception.AiException e = assertThrows(com.sure.ai.exception.AiException.class,
			client::listModels);
		assertTrue(e.getMessage().contains("IAMS"));
		assertTrue(this.lastPath.get() == null);
		client.close();
	}
}
