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

package com.sure.ai.zhipu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;

/**
 * {@link ZhipuClient} 与 {@link ZhipuJwtGenerator} 测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class ZhipuClientTest {

	private static final String API_KEY = "test-id.secure-secret";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastUri = new AtomicReference<>();
	private final AtomicInteger requestCount = new AtomicInteger();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/api/paas/v4";
		this.lastAuth.set(null);
		this.lastBody.set(null);
		this.lastUri.set(null);
		this.requestCount.set(0);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造指向 mock 的客户端。 */
	private ZhipuClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new ZhipuClient(cfg);
	}

	/** 注册处理器。 */
	private void handle(int status, String responseBody) {
		handle(ex -> respond(ex, status, responseBody));
	}

	/** 注册自定义处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.requestCount.incrementAndGet();
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

	/** JWT 解码校验：payload 中 api_key/id 正确、exp 合理，签名可独立验签。 */
	@Test
	public void testJwtGenerateAndVerify() throws Exception {
		String bearer = ZhipuJwtGenerator.generate(API_KEY);
		assertTrue(bearer.startsWith("Bearer "));
		String jwt = bearer.substring("Bearer ".length());
		String[] parts = jwt.split("\\.");
		assertEquals(3, parts.length);

		String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
		JsonObject header = Json.parse(headerJson).getAsJsonObject();
		assertEquals("HS256", header.getString("alg"));
		assertEquals("SIGN", header.getString("sign_type"));

		String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		JsonObject payload = Json.parse(payloadJson).getAsJsonObject();
		assertEquals("test-id", payload.getString("api_key"));
		long exp = payload.getInt("exp");
		long now = System.currentTimeMillis() / 1000L;
		assertTrue("exp should be ~3600s ahead", exp > now + 3000 && exp < now + 3700);
		long ts = payload.get("timestamp").getAsLong();
		assertTrue("timestamp should be ~now ms", Math.abs(ts - System.currentTimeMillis()) < 10_000L);

		// 独立用 secret 验签
		String signingInput = parts[0] + "." + parts[1];
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("secure-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] expected = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
		String expectedB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(expected);
		assertEquals(expectedB64, parts[2]);
	}

	/** apiKey 格式非法应抛出 AiException。 */
	@Test
	public void testJwtBadFormat() {
		assertThrows(RuntimeException.class, () -> ZhipuJwtGenerator.generate("no-dot"));
	}

	/** 请求头 Authorization 是 Bearer + JWT，而不是原始 apiKey。 */
	@Test
	public void testAuthorizationIsJwtNotRawKey() {
		handle(200, "{\"id\":\"c1\",\"model\":\"glm\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}");
		ZhipuClient client = newClient();
		client.chat(ChatRequest.builder().model("glm-4-flash").messages(ChatMessage.user("你好")).build());
		String auth = this.lastAuth.get();
		assertTrue(auth.startsWith("Bearer "));
		assertFalse("must not be raw apiKey", auth.equals("Bearer " + API_KEY));
		String jwt = auth.substring("Bearer ".length());
		assertEquals(3, jwt.split("\\.").length);
		client.close();
	}

	/** 正常 chat：请求体与响应解析。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"c2\",\"model\":\"glm-4-plus\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"你好，我是 GLM\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":6,\"total_tokens\":9}}");
		ZhipuClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("glm-4-plus")
			.messages(ChatMessage.user("你好")).build());
		assertTrue(this.lastBody.get().contains("\"model\":\"glm-4-plus\""));
		assertEquals("你好，我是 GLM", resp.firstText());
		assertEquals(9, resp.usage().totalTokens());
		client.close();
	}

	/** 流式 chat：聚合增量文本。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"c3\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"c3\",\"choices\":[{\"delta\":{\"content\":\"好\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"c3\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		ZhipuClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model("glm-4-flash").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("你好", sb.toString());
		client.close();
	}

	/** embeddings：请求体带 embedding-3。 */
	@Test
	public void testEmbeddings() {
		handle(200, "{\"model\":\"embedding-3\",\"data\":[{\"embedding\":[0.1,0.2,0.3]}],"
			+ "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":0,\"total_tokens\":2}}");
		ZhipuClient client = newClient();
		assertEquals(1, client.embed("embedding-3", "你好").embeddings().size());
		assertTrue(this.lastBody.get().contains("\"model\":\"embedding-3\""));
		client.close();
	}

	/** 图像生成：JWT Bearer 鉴权头、请求路径 /images/generations、响应解析正确。 */
	@Test
	public void testImageGeneration() {
		handle(200, "{\"created\":1700000000,\"data\":["
			+ "{\"url\":\"https://img.example.com/1.png\"}]}");
		ZhipuClient client = newClient();
		ImageResponse resp = client.generate(ImageRequest.builder()
			.model(ZhipuModels.COGVIEW_3).prompt("一只猫").build());
		String auth = this.lastAuth.get();
		assertTrue(auth.startsWith("Bearer "));
		assertFalse("must not be raw apiKey", auth.equals("Bearer " + API_KEY));
		assertTrue(this.lastUri.get().contains("/images/generations"));
		assertTrue(this.lastBody.get().contains("\"model\":\"cogview-3\""));
		assertTrue(this.lastBody.get().contains("\"prompt\":\"一只猫\""));
		assertEquals("https://img.example.com/1.png", resp.firstUrl());
		assertEquals(1700000000L, resp.created());
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"invalid token\"}");
		ZhipuClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("glm-4").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** JWT 缓存复用：连续两次 chat，Authorization 头应一致。 */
	@Test
	public void testTokenCachedAcrossCalls() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\"stop\"}]}");
		ZhipuClient client = newClient();
		client.chat(ChatRequest.builder().model("glm-4").messages(ChatMessage.user("1")).build());
		String first = this.lastAuth.get();
		client.chat(ChatRequest.builder().model("glm-4").messages(ChatMessage.user("2")).build());
		String second = this.lastAuth.get();
		assertEquals(first, second);
		assertNotEquals("Bearer " + API_KEY, second);
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("zhipu", newClient().name());
	}

	/** Models 常量非空。 */
	@Test
	public void testModelsConstants() {
		assertEquals("glm-4-plus", ZhipuModels.GLM_4_PLUS);
		assertEquals("embedding-3", ZhipuModels.EMBEDDING_3);
		List<String> ids = List.of(ZhipuModels.GLM_4_PLUS, ZhipuModels.GLM_4_FLASH,
			ZhipuModels.GLM_4_AIR, ZhipuModels.GLM_4_LONG, ZhipuModels.EMBEDDING_3);
		for (String id : ids) {
			assertFalse(id.isEmpty());
		}
	}
}
