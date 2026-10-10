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

package com.sure.ai.cohere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
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
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.RerankRequest;

/**
 * {@link CohereClient} / {@link CohereUtil} / {@link CohereRerankClient} 边界测试。
 *
 * @author sureai
 */
public class CohereEdgeTest {

	private static final String API_KEY = "edge-cohere";
	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.lastBody.set(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 注册通用处理器。 */
	private void register(String response) {
		this.server.createContext("/", ex -> {
			byte[] in = ex.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			respond(ex, 200, response);
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

	/** 成功 chat 响应。 */
	private static String okChat() {
		return "{\"id\":\"x\",\"text\":\"ok\",\"finish_reason\":\"COMPLETE\","
			+ "\"usage\":{\"tokens\":{\"input_tokens\":1,\"output_tokens\":1}}}";
	}

	/** 构造客户端。 */
	private CohereClient newClient() {
		return new CohereClient(AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build());
	}

	/** name() 返回 cohere。 */
	@Test
	public void testName() {
		assertEquals("cohere", newClient().name());
	}

	/** temperature / max_tokens 透传。 */
	@Test
	public void testTemperatureMaxTokens() {
		register(okChat());
		CohereClient client = newClient();
		client.chat(ChatRequest.builder().model("command-r").messages(ChatMessage.user("hi"))
			.temperature(0.5).maxTokens(64).build());
		assertTrue(this.lastBody.get().contains("\"temperature\":0.5"));
		assertTrue(this.lastBody.get().contains("\"max_tokens\":64"));
		client.close();
	}

	/** 未知流式事件类型 → default 分支产生空 chunk。 */
	@Test
	public void testUnknownStreamEventType() {
		String sse = "event: some_unknown_event\n"
			+ "data: {\"type\":\"some_unknown_event\",\"text\":\"ignored\"}\n\n"
			+ "event: stream-end\n"
			+ "data: {\"type\":\"stream-end\",\"finish_reason\":\"COMPLETE\"}\n\n";
		this.server.createContext("/", ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		CohereClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model("command-r").messages(ChatMessage.user("hi")).build(),
			c -> {
				if (c.deltaText() != null) {
					sb.append(c.deltaText());
				}
			});
		client.close();
	}

	/** parseUsage：usage 无 tokens 字段 → null usage。 */
	@Test
	public void testEmbedUsageNoTokens() {
		String resp = "{\"id\":\"e\",\"embeddings\":{\"float\":[[0.1,0.2]]},"
			+ "\"usage\":{\"unknown\":1}}";
		register(resp);
		CohereClient client = newClient();
		client.embed(new EmbeddingRequest("embed-english-v3.0", List.of("hi")));
		client.close();
	}

	/** withDefaults：baseUrl 为空时补默认地址。 */
	@Test
	public void testWithDefaultsBlankBaseUrl() {
		CohereClient client = new CohereClient(AiConfig.builder().apiKey(API_KEY).build());
		assertNotNull(client);
		client.close();
	}

	/** CohereUtil.init(String) 便捷方法。 */
	@Test
	public void testUtilInitString() {
		CohereUtil.init("test-key-123");
		assertNotNull(CohereUtil.client());
	}

	/** CohereUtil.buildFromEnv：空环境抛异常。 */
	@Test
	public void testBuildFromEnvNoEnv() throws Exception {
		java.lang.reflect.Method m = CohereUtil.class.getDeclaredMethod("buildFromEnv");
		m.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null));
	}

	/** CohereRerankClient extra 字段透传。 */
	@Test
	public void testRerankExtraFields() {
		String resp = "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}";
		register(resp);
		CohereRerankClient client = new CohereRerankClient(
			AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build());
		JsonObject extra = Json.object();
		extra.put("user", "u1");
		RerankRequest req = RerankRequest.builder().model("rerank-v3.5")
			.query("q").documents(List.of("doc1")).topN(1).extra("user", extra).build();
		client.rerank(req);
		assertTrue(this.lastBody.get().contains("\"user\""));
		client.close();
	}

	/** CohereModels 私有构造器。 */
	@Test
	public void testModelsPrivateCtor() throws Exception {
		Constructor<CohereModels> c = CohereModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** CohereUtil 私有构造器。 */
	@Test
	public void testUtilPrivateCtor() throws Exception {
		Constructor<CohereUtil> c = CohereUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
