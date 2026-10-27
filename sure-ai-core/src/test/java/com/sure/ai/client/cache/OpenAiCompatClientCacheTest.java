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

package com.sure.ai.client.cache;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * {@link OpenAiCompatClient} 缓存接入集成测试：本地 HttpServer mock，零真实网络。
 *
 * @author sureai
 * @since 1.1.0
 */
public class OpenAiCompatClientCacheTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicInteger requestCount = new AtomicInteger();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1";
		this.requestCount.set(0);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造带缓存的客户端。 */
	private OpenAiCompatClient newClient(CacheStore store, Duration ttl) {
		AiConfig.Builder b = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl);
		if (store != null) {
			b.cacheStore(store);
		}
		if (ttl != null) {
			b.cacheTtl(ttl);
		}
		return new OpenAiCompatClient(b.build());
	}

	/** 返回带自增 id 的成功响应。 */
	private void handleSuccess() {
		this.server.createContext("/", exchange -> {
			this.requestCount.incrementAndGet();
			int n = this.requestCount.get();
			String body = "{\"id\":\"c" + n + "\",\"model\":\"gpt\",\"choices\":[{\"index\":0,"
				+ "\"message\":{\"role\":\"assistant\",\"content\":\"ans-" + n + "\"},"
				+ "\"finish_reason\":\"stop\"}]}";
			respond(exchange, 200, body);
		});
	}

	/** 始终返回 500。 */
	private void handleError() {
		this.server.createContext("/", exchange -> {
			this.requestCount.incrementAndGet();
			respond(exchange, 500, "{\"error\":\"boom\"}");
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

	/** 基础非流式请求。 */
	private static ChatRequest req(String prompt) {
		return ChatRequest.builder().model("gpt").messages(ChatMessage.user(prompt)).build();
	}

	/** 缓存命中跳过网络：第二次相同请求不触发 HTTP，返回缓存响应。 */
	@Test
	public void testCacheHitSkipsNetwork() {
		handleSuccess();
		OpenAiCompatClient client = newClient(new LruCacheStore(), null);
		ChatResponse r1 = client.chat(req("hi"));
		assertEquals(1, this.requestCount.get());
		assertEquals("c1", r1.id());
		ChatResponse r2 = client.chat(req("hi"));
		// 未触发新 HTTP
		assertEquals(1, this.requestCount.get());
		// 返回的是第一次的缓存响应（id 仍为 c1，而非自增后的 c2）
		assertEquals("c1", r2.id());
		assertEquals("ans-1", r2.firstText());
		client.close();
	}

	/** 缓存未命中走网络：不同请求触发新 HTTP。 */
	@Test
	public void testCacheMissGoesToNetwork() {
		handleSuccess();
		OpenAiCompatClient client = newClient(new LruCacheStore(), null);
		client.chat(req("one"));
		client.chat(req("two"));
		assertEquals(2, this.requestCount.get());
		client.close();
	}

	/** TTL 过期后再次请求触发新 HTTP。 */
	@Test
	public void testCacheTtlExpiry() throws InterruptedException {
		handleSuccess();
		OpenAiCompatClient client = newClient(new LruCacheStore(16, 60_000),
			Duration.ofMillis(150));
		client.chat(req("hi"));
		assertEquals(1, this.requestCount.get());
		Thread.sleep(250);
		client.chat(req("hi"));
		assertEquals(2, this.requestCount.get());
		client.close();
	}

	/** 默认不配置 cacheStore：每次请求都触发 HTTP（行为与以前一致）。 */
	@Test
	public void testCacheDisabledByDefault() {
		handleSuccess();
		OpenAiCompatClient client = newClient(null, null);
		client.chat(req("hi"));
		client.chat(req("hi"));
		assertEquals(2, this.requestCount.get());
		client.close();
	}

	/** stream=true 不查缓存：每次都发请求。 */
	@Test
	public void testStreamNotCached() {
		// chat 走缓存；这里直接用非流式 chat 但把请求标 stream=true，
		// 验证即便请求标记流式，chat() 路径也不读缓存（仍走网络）。
		handleSuccess();
		OpenAiCompatClient client = newClient(new LruCacheStore(), null);
		ChatRequest streamReq = ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user("hi")).stream(true).build();
		client.chat(streamReq);
		client.chat(streamReq);
		assertEquals(2, this.requestCount.get());
		client.close();
	}

	/** 错误响应不缓存：500 重试失败后，下次仍发请求。 */
	@Test
	public void testErrorNotCached() {
		handleError();
		OpenAiCompatClient client = newClient(new LruCacheStore(), null);
		// 第一次：maxRetries=2，共 3 次尝试均 500，抛出
		assertEquals(0, this.requestCount.get());
		org.junit.Assert.assertThrows(AiApiException.class, () -> client.chat(req("hi")));
		int afterFirst = this.requestCount.get();
		org.junit.Assert.assertTrue(afterFirst >= 1);
		// 第二次：错误未入缓存，仍发请求
		org.junit.Assert.assertThrows(AiApiException.class, () -> client.chat(req("hi")));
		org.junit.Assert.assertTrue(this.requestCount.get() > afterFirst);
		client.close();
	}
}
