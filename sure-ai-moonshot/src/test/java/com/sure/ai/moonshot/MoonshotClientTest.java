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

package com.sure.ai.moonshot;

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
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * {@link MoonshotClient} 与 {@link MoonshotUtil} 测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class MoonshotClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1";
		this.lastAuth.set(null);
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
	@SuppressWarnings("unchecked")
	private static void setSingleton(MoonshotClient c) {
		try {
			Field f = MoonshotUtil.class.getDeclaredField("HOLDER");
			f.setAccessible(true);
			SingletonHolder<MoonshotClient> holder = (SingletonHolder<MoonshotClient>) f.get(null);
			if (c == null) {
				holder.reset();
			} else {
				holder.set(c);
			}
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 构造指向 mock 的客户端。 */
	private MoonshotClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("ms-test-key").baseUrl(this.baseUrl).build();
		return new MoonshotClient(cfg);
	}

	/** 注册处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
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

	/** Bearer 头：直接是 apiKey。 */
	@Test
	public void testBearerHeader() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}");
		MoonshotClient client = newClient();
		client.chat(ChatRequest.builder().model("kimi-k2").messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer ms-test-key", this.lastAuth.get());
		client.close();
	}

	/** 正常 chat。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"c\",\"model\":\"kimi-k2\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}");
		MoonshotClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("kimi-k2")
			.messages(ChatMessage.user("你好")).build());
		assertEquals("你好", resp.firstText());
		assertTrue(this.lastBody.get().contains("\"model\":\"kimi-k2\""));
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
		MoonshotClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model("moonshot-v1-8k").messages(ChatMessage.user("hi")).build(),
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
		handle(200, "{\"model\":\"moonshot-embedding-v1\",\"data\":[{\"embedding\":[0.1,0.2,0.3]}],"
			+ "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":0,\"total_tokens\":2}}");
		MoonshotClient client = newClient();
		EmbeddingResponse resp = client.embed(
			new EmbeddingRequest("moonshot-embedding-v1", List.of("hi")));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		client.close();
	}

	/** 401 映射。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"bad key\"}");
		MoonshotClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("kimi-k2").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** name() 与默认 baseUrl。 */
	@Test
	public void testNameAndDefaultBaseUrl() {
		assertEquals("moonshot", newClient().name());
		assertEquals("https://api.moonshot.cn/v1", MoonshotClient.DEFAULT_BASE_URL);
	}

	/** Models 常量。 */
	@Test
	public void testModelsConstants() {
		assertEquals("kimi-k2", MoonshotModels.KIMI_K2);
		assertEquals("moonshot-embedding-v1", MoonshotModels.MOONSHOT_EMBEDDING_V1);
		List<String> ids = List.of(MoonshotModels.KIMI_K2, MoonshotModels.KIMI_K2_5,
			MoonshotModels.MOONSHOT_V1_8K, MoonshotModels.MOONSHOT_V1_32K,
			MoonshotModels.MOONSHOT_V1_128K, MoonshotModels.MOONSHOT_EMBEDDING_V1);
		for (String id : ids) {
			assertFalse(id.isEmpty());
		}
	}

	/** Util：init + 便捷方法。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		AiConfig cfg = AiConfig.builder().apiKey("ms").baseUrl(this.baseUrl).build();
		setSingleton(new MoonshotClient(cfg));
		assertEquals("ok", MoonshotUtil.chat("kimi-k2", "hi").firstText());
		assertNotNull(MoonshotUtil.client());
	}

	/** Util：init(String) 不触网。 */
	@Test
	public void testUtilInitByKey() {
		MoonshotUtil.init("ms-key");
		assertNotNull(MoonshotUtil.client());
	}

	/** 未传 baseUrl 时客户端使用默认地址（构造不触网）。 */
	@Test
	public void testDefaultBaseUrlApplied() {
		MoonshotClient client = new MoonshotClient(AiConfig.of("ms-key"));
		assertEquals("moonshot", client.name());
		client.close();
	}

	/** Util：未注入时 client() 走环境变量懒加载（有 env 则构造成功，无 env 则抛 AiException，均覆盖分支）。 */
	@Test
	public void testUtilLazyFromEnv() {
		setSingleton(null);
		if (System.getenv(MoonshotUtil.ENV_API_KEY) == null) {
			assertThrows(com.sure.ai.exception.AiException.class, MoonshotUtil::client);
		} else {
			assertNotNull(MoonshotUtil.client());
		}
	}

	/** Util：init(AiConfig) + chat(ChatRequest) + chatStream + embed 全链路。 */
	@Test
	public void testUtilFullConvenience() {
		this.server.createContext("/conv", exchange -> {
			String path = exchange.getRequestURI().getPath();
			byte[] in = exchange.getRequestBody().readAllBytes();
			String req = new String(in, StandardCharsets.UTF_8);
			byte[] bytes;
			if (path.endsWith("/embeddings")) {
				String body = "{\"model\":\"me\",\"data\":[{\"embedding\":[0.1,0.2]}],"
					+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"total_tokens\":1}}";
				bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
			} else if (req.contains("\"stream\":true")) {
				String sse = "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"ok\"},\"index\":0}]}\n\n"
					+ "data: [DONE]\n\n";
				bytes = sse.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			} else {
				String body = "{\"id\":\"c\",\"choices\":[{\"index\":0,"
					+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
				bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
			}
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		String convBase = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/conv";
		MoonshotUtil.init(AiConfig.builder().apiKey("ms").baseUrl(convBase).build());
		ChatResponse r = MoonshotUtil.chat(ChatRequest.builder().model("kimi-k2")
			.messages(ChatMessage.user("hi")).build());
		assertEquals("ok", r.firstText());
		StringBuilder sb = new StringBuilder();
		MoonshotUtil.chatStream(ChatRequest.builder().model("kimi-k2").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("ok", sb.toString());
		assertEquals(1, MoonshotUtil.embed(new EmbeddingRequest("me", List.of("x"))).embeddings().size());
	}
}
