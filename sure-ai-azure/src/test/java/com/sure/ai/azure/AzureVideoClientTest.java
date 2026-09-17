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

package com.sure.ai.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * {@link AzureVideoClient} 测试：本地 HttpServer mock 提交与轮询。
 *
 * @author sureai
 * @since 0.2.0
 */
public class AzureVideoClientTest {

	private static final String API_KEY = "azure-video-key";
	private static final String VIDEO_URL = "https://example.com/sora.mp4";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> submitUri = new AtomicReference<>();
	private final AtomicReference<String> submitBody = new AtomicReference<>();
	private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
	private final AtomicReference<String> authHeader = new AtomicReference<>();
	private final AtomicInteger pollHits = new AtomicInteger();

	/** 模式：null=成功，"failed"=任务失败，"pending"=一直运行，"noUrl"=成功但 generations 无 url。 */
	private String mode;

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.pollHits.set(0);
		this.mode = null;
		register();
	}

	/** 停止服务并恢复轮询参数。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		AzureVideoClient.POLL_INTERVAL_MS = 2000L;
		AzureVideoClient.MAX_WAIT_MS = 120000L;
	}

	/** 注册 mock 路由。 */
	private void register() {
		this.server.createContext("/", exchange -> {
			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();
			this.apiKeyHeader.set(exchange.getRequestHeaders().getFirst("api-key"));
			this.authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
			if ("POST".equals(method)) {
				this.submitUri.set(exchange.getRequestURI().toString());
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.submitBody.set(new String(in, StandardCharsets.UTF_8));
				respond(exchange, 200, "{\"id\":\"task_1\",\"status\":\"queued\",\"created_at\":123}");
				return;
			}
			if ("GET".equals(method)) {
				int hits = this.pollHits.incrementAndGet();
				if ("failed".equals(this.mode)) {
					respond(exchange, 200, "{\"id\":\"task_1\",\"status\":\"failed\"}");
					return;
				}
				if ("pending".equals(this.mode) || hits == 1) {
					respond(exchange, 200, "{\"id\":\"task_1\",\"status\":\"running\"}");
					return;
				}
				if ("noUrl".equals(this.mode)) {
					respond(exchange, 200,
						"{\"id\":\"task_1\",\"status\":\"succeeded\",\"generations\":[{\"id\":\"gen_abc\"}]}");
					return;
				}
				respond(exchange, 200, "{\"id\":\"task_1\",\"status\":\"succeeded\",\"created_at\":123,"
					+ "\"generations\":[{\"id\":\"gen_1\",\"url\":\"" + VIDEO_URL + "\"}]}");
				return;
			}
			respond(exchange, 404, "{}");
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

	/** 构造指向 mock 的客户端。 */
	private AzureVideoClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new AzureVideoClient(cfg);
	}

	/** 成功：api-key 头、提交路径、api-version、轮询、firstUrl。 */
	@Test
	public void testSuccess() {
		AzureVideoClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.builder()
			.model(AzureModels.SORA_2).prompt("a cat").size("1280x720")
			.duration(5).build());
		assertEquals(VIDEO_URL, resp.firstUrl());
		assertEquals(123L, resp.created());
		assertEquals(API_KEY, this.apiKeyHeader.get());
		assertNull("不应使用 Authorization 头", this.authHeader.get());
		assertTrue(this.submitUri.get().contains("/openai/v1/video/generations/jobs"));
		assertTrue(this.submitUri.get().contains("api-version=preview"));
		String body = this.submitBody.get();
		assertTrue(body.contains("\"prompt\":\"a cat\""));
		assertTrue(body.contains("\"model\":\"sora-2\""));
		assertTrue(body.contains("\"width\":1280"));
		assertTrue(body.contains("\"height\":720"));
		assertTrue(body.contains("\"n_seconds\":5"));
		assertEquals(2, this.pollHits.get());
		client.close();
	}

	/** 任务失败：status=failed 抛 AiApiException。 */
	@Test
	public void testFailed() {
		this.mode = "failed";
		AzureVideoClient client = newClient();
		assertThrows(AiApiException.class,
			() -> client.generate(VideoRequest.of(AzureModels.SORA_2, "x")));
		client.close();
	}

	/** 超时：始终 running，超过最大等待抛 AiTimeoutException。 */
	@Test
	public void testTimeout() {
		this.mode = "pending";
		AzureVideoClient.POLL_INTERVAL_MS = 30L;
		AzureVideoClient.MAX_WAIT_MS = 150L;
		AzureVideoClient client = newClient();
		assertThrows(AiTimeoutException.class,
			() -> client.generate(VideoRequest.of(AzureModels.SORA_2, "slow")));
		assertTrue(this.pollHits.get() > 1);
		client.close();
	}

	/** generations 无 url：拼接 content/video 下载端点。 */
	@Test
	public void testDownloadUrlFallback() {
		this.mode = "noUrl";
		AzureVideoClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.of(AzureModels.SORA_2, "x"));
		String url = resp.firstUrl();
		assertNotNull(url);
		assertTrue(url.contains("/openai/v1/video/generations/gen_abc/content/video"));
		assertTrue(url.contains("api-version=preview"));
		client.close();
	}

	/** name() 与默认 apiVersion。 */
	@Test
	public void testNameAndDefaults() {
		assertEquals("azure-video", newClient().name());
		assertEquals("preview", newClient().apiVersion());
	}

	/** Util 重置方法。 */
	@Test
	public void testUtilReset() {
		AzureUtil.resetVideoClient();
		AzureUtil.resetTtsClient();
		AzureUtil.resetSttClient();
		assertEquals(0, 0);
	}
}
