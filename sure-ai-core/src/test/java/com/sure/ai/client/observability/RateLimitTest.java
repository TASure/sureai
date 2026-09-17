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

package com.sure.ai.client.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

/**
 * 客户端限流（AiConfig.rateLimitQps + RateLimiter）测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.3.0
 */
public class RateLimitTest {

	private static final String OK = "{\"id\":\"c\",\"model\":\"gpt\",\"choices\":[{\"index\":0,"
		+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

	private HttpServer server;
	private String baseUrl;

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		this.server.createContext("/", exchange -> {
			byte[] bytes = OK.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 读取 AbstractAiClient 的 rateLimiter 字段。 */
	private static Object rateLimiterOf(OpenAiCompatClient c) throws Exception {
		Field f = c.getClass().getSuperclass().getDeclaredField("rateLimiter");
		f.setAccessible(true);
		return f.get(c);
	}

	/** 发一次 chat。 */
	private static void chat(OpenAiCompatClient c) {
		c.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build());
	}

	/** 启用限流：RateLimiter 被创建，请求正常。 */
	@Test
	public void testRateLimitEnabled() throws Exception {
		OpenAiCompatClient c = new OpenAiCompatClient(AiConfig.builder().apiKey("k")
			.baseUrl(this.baseUrl).rateLimitQps(100).build());
		assertNotNull("rateLimiter should be created", rateLimiterOf(c));
		chat(c);
		c.close();
	}

	/** 默认关闭：不创建 RateLimiter，请求立即完成。 */
	@Test
	public void testRateLimitDisabled() throws Exception {
		OpenAiCompatClient c = new OpenAiCompatClient(AiConfig.builder().apiKey("k")
			.baseUrl(this.baseUrl).build());
		assertNull("rateLimiter should be null by default", rateLimiterOf(c));
		long start = System.nanoTime();
		chat(c);
		long ms = (System.nanoTime() - start) / 1_000_000L;
		assertTrue("disabled rate limit should be fast, but took " + ms + "ms", ms < 500);
		c.close();
	}

	/** 低 QPS 限流生效：QPS=1，连发两次请求第二次需等待约 1s。 */
	@Test
	public void testRateLimitThrottling() {
		OpenAiCompatClient c = new OpenAiCompatClient(AiConfig.builder().apiKey("k")
			.baseUrl(this.baseUrl).rateLimitQps(1).build());
		long start = System.nanoTime();
		chat(c);
		chat(c);
		long ms = (System.nanoTime() - start) / 1_000_000L;
		// 桶初始 1 令牌，第二次需等待约 1s
		assertTrue("expected throttling >= 500ms but took " + ms + "ms", ms >= 500);
		c.close();
	}

	/** rateLimitQps getter 默认 0、设置后生效。 */
	@Test
	public void testConfigGetters() {
		AiConfig off = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build();
		assertEquals(0.0, off.rateLimitQps(), 0.0);
		AiConfig on = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).rateLimitQps(5).build();
		assertEquals(5.0, on.rateLimitQps(), 0.0);
	}
}
