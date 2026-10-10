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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.Capability;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;

/**
 * {@link SureAiProxy} 端点错误分支补齐：405 方法不允许、embeddings 的 400/501/502、
 * 流式上游错误、2 参构造器（无注册表）、{@link SureAiProxy#main} 与 {@link ProxyConfig#load}。
 *
 * <p>零真实网络：全部本地回环端口 + fake 客户端。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class ProxyEdgeCoverageTest {

	private static final String KEY = "sk-edge";

	private final HttpClient http = HttpClient.newHttpClient();
	private SureAiProxy proxy;
	private String base;

	/** 构造带注册表（含 chat + embed）的代理。 */
	private void start(ClientRegistry registry) throws Exception {
		Properties p = new Properties();
		p.setProperty("proxy.port", "0");
		p.setProperty("proxy.default.model", "gpt-4o");
		p.setProperty("proxy.models", "gpt-4o");
		p.setProperty("proxy.key." + KEY, "tenant1");
		ProxyConfig config = ProxyConfig.fromProperties(p);
		this.proxy = new SureAiProxy(new GatewayClient(registry), registry, config);
		this.proxy.start();
		this.base = "http://localhost:" + this.proxy.boundPort();
	}

	@After
	public void tearDown() {
		if (this.proxy != null) {
			this.proxy.stop();
		}
	}

	/** GET /v1/chat/completions → 405。 */
	@Test
	public void chatEndpointRejectsNonPost() throws Exception {
		start(new ClientRegistry());
		HttpRequest req = HttpRequest.newBuilder(URI.create(this.base + "/v1/chat/completions"))
			.header("Authorization", "Bearer " + KEY)
			.GET()
			.build();
		HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(405, resp.statusCode());
		assertTrue(resp.body().contains("method_not_allowed"));
	}

	/** POST /v1/models → 405。 */
	@Test
	public void modelsEndpointRejectsNonGet() throws Exception {
		start(new ClientRegistry());
		HttpRequest req = HttpRequest.newBuilder(URI.create(this.base + "/v1/models"))
			.header("Authorization", "Bearer " + KEY)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{}"))
			.build();
		HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(405, resp.statusCode());
		assertTrue(resp.body().contains("method_not_allowed"));
	}

	/** GET /v1/embeddings → 405。 */
	@Test
	public void embeddingsEndpointRejectsNonPost() throws Exception {
		start(new ClientRegistry());
		HttpRequest req = HttpRequest.newBuilder(URI.create(this.base + "/v1/embeddings"))
			.header("Authorization", "Bearer " + KEY)
			.GET()
			.build();
		HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(405, resp.statusCode());
		assertTrue(resp.body().contains("method_not_allowed"));
	}

	/** 无鉴权访问 /v1/models → 401。 */
	@Test
	public void modelsWithoutAuthReturns401() throws Exception {
		start(new ClientRegistry());
		HttpRequest req = HttpRequest.newBuilder(URI.create(this.base + "/v1/models"))
			.GET()
			.build();
		HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode());
	}

	/** embeddings 请求体非 JSON → 400。 */
	@Test
	public void embeddingsMalformedBodyReturns400() throws Exception {
		start(new ClientRegistry());
		HttpResponse<String> resp = postEmbed("{bad", KEY);
		assertEquals(400, resp.statusCode());
	}

	/** embeddings 缺少 input → 400。 */
	@Test
	public void embeddingsMissingInputReturns400() throws Exception {
		start(new ClientRegistry());
		HttpResponse<String> resp = postEmbed("{\"model\":\"m\"}", KEY);
		assertEquals(400, resp.statusCode());
		assertTrue(resp.body().contains("input"));
	}

	/** embeddings input 为对象 → 400。 */
	@Test
	public void embeddingsObjectInputReturns400() throws Exception {
		start(new ClientRegistry());
		HttpResponse<String> resp = postEmbed("{\"input\":{\"a\":1}}", KEY);
		assertEquals(400, resp.statusCode());
	}

	/** 2 参构造器（registry=null）：embeddings 无客户端 → 501。 */
	@Test
	public void embeddingsNoRegistryReturns501() throws Exception {
		Properties p = new Properties();
		p.setProperty("proxy.port", "0");
		p.setProperty("proxy.key." + KEY, "tenant1");
		ProxyConfig config = ProxyConfig.fromProperties(p);
		this.proxy = new SureAiProxy(new GatewayClient(new ClientRegistry()), config);
		this.proxy.start();
		this.base = "http://localhost:" + this.proxy.boundPort();
		HttpResponse<String> resp = postEmbed("{\"input\":[\"hi\"],\"model\":\"m\"}", KEY);
		assertEquals(501, resp.statusCode());
		assertTrue(resp.body().contains("no_embedding_client"));
	}

	/** 注册表声明了 EMBED 能力但客户端不实现 EmbeddingClient → 501（遍历分支）。 */
	@Test
	public void embeddingsRegistryWithoutEmbeddingClientReturns501() throws Exception {
		ClientRegistry registry = new ClientRegistry();
		registry.register("chat-only", new PlainChatClient(), Set.of(Capability.EMBED));
		start(registry);
		HttpResponse<String> resp = postEmbed("{\"input\":[\"hi\"],\"model\":\"m\"}", KEY);
		assertEquals(501, resp.statusCode());
	}

	/** embeddings 上游抛 AiException → 502。 */
	@Test
	public void embeddingsUpstreamErrorReturns502() throws Exception {
		ClientRegistry registry = new ClientRegistry();
		registry.register("boom-embed", new ThrowingEmbeddingClient(), Set.of(Capability.EMBED));
		start(registry);
		HttpResponse<String> resp = postEmbed("{\"input\":[\"hi\"],\"model\":\"m\"}", KEY);
		assertEquals(502, resp.statusCode());
		assertTrue(resp.body().contains("upstream_error"));
	}

	/** input 为字符串数组：正常路由（覆盖数组分支）。 */
	@Test
	public void embeddingsArrayInputSuccess() throws Exception {
		ClientRegistry registry = new ClientRegistry();
		registry.register("embed", new FakeEmbeddingClient(), Set.of(Capability.EMBED));
		start(registry);
		HttpResponse<String> resp = postEmbed(
			"{\"input\":[\"hello\",\"world\"],\"model\":\"embed-1\"}", KEY);
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("\"object\":\"embedding\""));
	}

	/** 流式上游在 chatStream 抛异常：SSE 写入错误事件，不崩溃。 */
	@Test
	public void streamUpstreamErrorWritesSseError() throws Exception {
		ClientRegistry registry = new ClientRegistry();
		RecordingChatClient chat = new RecordingChatClient();
		registry.register("chat", chat, Set.of(Capability.CHAT, Capability.CHAT_STREAM));
		start(registry);
		chat.throwOn(new AiException("stream-boom"));
		String body = "{\"model\":\"gpt-4o\",\"stream\":true,"
			+ "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
		HttpResponse<String> resp = post("/v1/chat/completions", body, KEY);
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("data: "));
		assertTrue(resp.body().contains("stream-boom"));
	}

	/** {@link ProxyConfig#load} 从 properties 文件读取；端口缺省回退。 */
	@Test
	public void loadConfigFromFile() throws Exception {
		Path file = Files.createTempFile("sureai-proxy", ".properties");
		String content = "proxy.default.model=file-model\nproxy.models=a,b\n";
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		try {
			ProxyConfig config = ProxyConfig.load(file);
			assertEquals(ProxyConfig.DEFAULT_PORT, config.port());
			assertEquals("file-model", config.defaultModel());
			assertEquals(2, config.exposedModels().size());
		} finally {
			Files.deleteIfExists(file);
		}
	}

	/** {@link SureAiProxy#main} 以临时 properties（端口 0）启动不抛异常。 */
	@Test
	public void mainStartsWithTempConfig() throws Exception {
		Path file = Files.createTempFile("sureai-proxy-main", ".properties");
		Files.write(file, "proxy.port=0\n".getBytes(StandardCharsets.UTF_8));
		try {
			// 绑定临时端口启动；main 不调用 System.exit，启动成功即覆盖其主体。
			SureAiProxy.main(new String[] { file.toString() });
		} finally {
			Files.deleteIfExists(file);
		}
	}

	/** POST 助手。 */
	private HttpResponse<String> post(String path, String body, String key) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(this.base + path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (key != null) {
			b.header("Authorization", "Bearer " + key);
		}
		return this.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** POST /v1/embeddings 助手。 */
	private HttpResponse<String> postEmbed(String body, String key) throws Exception {
		return post("/v1/embeddings", body, key);
	}

	/** 仅实现 AiClient、声明 EMBED 能力但不是 EmbeddingClient。 */
	private static final class PlainChatClient implements AiClient {
		@Override
		public String name() {
			return "plain";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void chatStream(ChatRequest request, java.util.function.Consumer<ChatStreamChunk> c) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
		}
	}

	/** embed 抛 AiException 的 EmbeddingClient。 */
	private static final class ThrowingEmbeddingClient implements AiClient, EmbeddingClient {
		@Override
		public String name() {
			return "boom-embed";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void chatStream(ChatRequest request, java.util.function.Consumer<ChatStreamChunk> c) {
			throw new UnsupportedOperationException();
		}

		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			throw new AiException("embed-boom");
		}

		@Override
		public void close() {
		}
	}
}
