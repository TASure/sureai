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
package com.sure.ai.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.client.Capability;
import com.sure.ai.exception.AiException;
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.GatewayClient;

/**
 * {@link SureAiProxy} 端到端测试（零真实网络：本地端口 + fake 客户端）。
 *
 * @author sureai
 * @since 1.6.0
 */
public class ProxyServerTest {

	private static final String VALID_KEY = "sk-test-key";
	private static final String CHAT_BODY = "{\"model\":\"gpt-4o\","
			+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

	private final HttpClient http = HttpClient.newHttpClient();
	private RecordingChatClient chatClient;
	private FakeEmbeddingClient embedClient;
	private SureAiProxy proxy;
	private String base;

	/** 每个测试独立启动一个代理实例（端口 0 = 临时端口）。 */
	@Before
	public void setUp() throws Exception {
		Properties p = new Properties();
		p.setProperty("proxy.port", "0");
		p.setProperty("proxy.default.model", "gpt-4o");
		p.setProperty("proxy.models", "gpt-4o,text-embedding-3-small");
		p.setProperty("proxy.key." + VALID_KEY, "tenant1");
		ProxyConfig config = ProxyConfig.fromProperties(p);

		this.chatClient = new RecordingChatClient();
		this.embedClient = new FakeEmbeddingClient();
		ClientRegistry registry = new ClientRegistry();
		registry.register("fake", this.chatClient,
				Set.of(Capability.CHAT, Capability.CHAT_STREAM));
		registry.register("embed", this.embedClient, Set.of(Capability.EMBED));

		this.proxy = new SureAiProxy(new GatewayClient(registry), registry, config);
		this.proxy.start();
		this.base = "http://localhost:" + this.proxy.boundPort();
	}

	@After
	public void tearDown() {
		this.proxy.stop();
	}

	/** 非流式 chat：200 + OpenAI choices/usage + model/tenant 透传。 */
	@Test
	public void testChatCompletionNonStream() throws Exception {
		HttpResponse<String> resp = post("/v1/chat/completions", CHAT_BODY, VALID_KEY);
		assertEquals(200, resp.statusCode());
		assertTrue(resp.headers().firstValue("Content-Type").orElse("")
			.contains("application/json"));
		assertTrue(resp.body().contains("\"object\":\"chat.completion\""));
		assertTrue(resp.body().contains("\"role\":\"assistant\""));
		assertTrue(resp.body().contains("\"content\":\"hello back\""));
		assertTrue(resp.body().contains("\"prompt_tokens\":11"));
		assertTrue(resp.body().contains("\"total_tokens\":33"));
		assertEquals("gpt-4o", this.chatClient.lastModel());
		assertEquals("tenant1", this.chatClient.lastTenantId());
	}

	/** 流式 chat：SSE 事件流 + data: 前缀 + 末尾 [DONE]。 */
	@Test
	public void testChatCompletionStream() throws Exception {
		String body = "{\"model\":\"gpt-4o\",\"stream\":true,"
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
		HttpResponse<String> resp = post("/v1/chat/completions", body, VALID_KEY);
		assertEquals(200, resp.statusCode());
		assertTrue(resp.headers().firstValue("Content-Type").orElse("")
			.contains("text/event-stream"));
		assertTrue(resp.body().contains("data: "));
		assertTrue(resp.body().contains("\"delta\""));
		assertTrue(resp.body().contains("\"finish_reason\":\"stop\""));
		assertTrue(resp.body().trim().endsWith("data: [DONE]"));
		assertTrue(this.chatClient.lastStream());
	}

	/** 缺失或错误的 API key 返回 401 + OpenAI 错误格式。 */
	@Test
	public void testInvalidApiKeyReturns401() throws Exception {
		HttpResponse<String> missing = post("/v1/chat/completions", CHAT_BODY, null);
		assertEquals(401, missing.statusCode());
		assertTrue(missing.body().contains("\"error\""));
		assertTrue(missing.body().contains("Invalid API key"));

		HttpResponse<String> wrong = post("/v1/chat/completions", CHAT_BODY, "nope");
		assertEquals(401, wrong.statusCode());
		assertTrue(wrong.body().contains("\"error\""));
	}

	/** GET /v1/models 返回模型列表。 */
	@Test
	public void testModelsEndpoint() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(URI.create(this.base + "/v1/models"))
			.header("Authorization", "Bearer " + VALID_KEY)
			.GET()
			.build();
		HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("\"object\":\"list\""));
		assertTrue(resp.body().contains("\"id\":\"gpt-4o\""));
		assertTrue(resp.body().contains("text-embedding-3-small"));
	}

	/** 上游 AiException 转换为 OpenAI 错误格式。 */
	@Test
	public void testGatewayErrorConvertedToOpenAIFormat() throws Exception {
		this.chatClient.throwOn(new AiException("boom-upstream"));
		HttpResponse<String> resp = post("/v1/chat/completions", CHAT_BODY, VALID_KEY);
		assertEquals(502, resp.statusCode());
		assertTrue(resp.body().contains("\"error\""));
		assertTrue(resp.body().contains("boom-upstream"));
	}

	/** 请求体 model 字段原样传递给 GatewayClient。 */
	@Test
	public void testModelRoutingViaRequestModel() throws Exception {
		String body = "{\"model\":\"gpt-4o-mini\","
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
		HttpResponse<String> resp = post("/v1/chat/completions", body, VALID_KEY);
		assertEquals(200, resp.statusCode());
		assertEquals("gpt-4o-mini", this.chatClient.lastModel());
	}

	/** POST /v1/embeddings 返回 OpenAI embedding 格式。 */
	@Test
	public void testEmbeddingsEndpoint() throws Exception {
		String body = "{\"model\":\"text-embedding-3-small\",\"input\":\"hello\"}";
		HttpResponse<String> resp = post("/v1/embeddings", body, VALID_KEY);
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("\"object\":\"list\""));
		assertTrue(resp.body().contains("\"object\":\"embedding\""));
		assertTrue(resp.body().contains("\"index\":0"));
		assertTrue(resp.body().contains("\"embedding\":["));
		assertEquals("text-embedding-3-small", this.embedClient.lastModel());
	}

	/** 带错误 JSON 的请求返回 400。 */
	@Test
	public void testMalformedBodyReturns400() throws Exception {
		HttpResponse<String> resp = post("/v1/chat/completions", "{not json", VALID_KEY);
		assertEquals(400, resp.statusCode());
		assertTrue(resp.body().contains("\"error\""));
	}

	/** POST 助手。 */
	private HttpResponse<String> post(String path, String body, String key) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(URI.create(this.base + path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (key != null) {
			b.header("Authorization", "Bearer " + key);
		}
		return this.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}
}
