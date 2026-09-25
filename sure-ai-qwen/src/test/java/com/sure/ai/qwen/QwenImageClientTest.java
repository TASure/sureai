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

package com.sure.ai.qwen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;

/**
 * {@link QwenImageClient} 测试：本地 HttpServer mock 提交与轮询接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenImageClientTest {

	private static final String API_KEY = "test-key";
	private static final String IMAGE_URL = "https://example.com/result.png";

	private HttpServer server;
	private String baseUrl;
	private final AtomicInteger pollHits = new AtomicInteger();
	private final AtomicReference<String> submitHeaderAuth = new AtomicReference<>();
	private final AtomicReference<String> submitHeaderAsync = new AtomicReference<>();
	private final AtomicReference<String> submitBody = new AtomicReference<>();

	/** 失败模式：null=正常，"failed"=任务失败，"pending"=一直等待。 */
	private String mode;

	/** 启动本地 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.pollHits.set(0);
		this.mode = null;
		registerHandlers();
	}

	/** 停止服务并恢复轮询参数。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		QwenImageClient.POLL_INTERVAL_MS = 2000L;
		QwenImageClient.MAX_WAIT_MS = 120000L;
		QwenUtil.resetImageClient();
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/api/v1/services/aigc/text2image/image-synthesis")) {
				this.submitHeaderAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				this.submitHeaderAsync.set(exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.submitBody.set(new String(in, StandardCharsets.UTF_8));
				String body = "{\"output\":{\"task_id\":\"task-123\"},\"request_id\":\"req-1\"}";
				respond(exchange, 200, body, "application/json");
				return;
			}
			if (path.equals("/api/v1/tasks/task-123")) {
				int hits = this.pollHits.incrementAndGet();
				if ("failed".equals(this.mode)) {
					String body = "{\"output\":{\"task_status\":\"FAILED\",\"message\":\"bad prompt\"},"
						+ "\"request_id\":\"req-2\"}";
					respond(exchange, 200, body, "application/json");
					return;
				}
				if ("pending".equals(this.mode) || hits == 1) {
					String body = "{\"output\":{\"task_status\":\"PENDING\"},\"request_id\":\"req-2\"}";
					respond(exchange, 200, body, "application/json");
					return;
				}
				String body = "{\"output\":{\"task_status\":\"SUCCEEDED\","
					+ "\"results\":[{\"url\":\"" + IMAGE_URL + "\"}]},\"request_id\":\"req-3\","
					+ "\"usage\":{\"image_count\":1}}";
				respond(exchange, 200, body, "application/json");
				return;
			}
			respond(exchange, 404, "{}", "application/json");
		});
	}

	/** 发送响应。 */
	private static void respond(HttpExchange ex, int status, String body, String contentType)
			throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 构造指向 mock 的客户端。 */
	private QwenImageClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new QwenImageClient(cfg);
	}

	/** 成功：提交头正确，轮询两次后返回图片 URL。 */
	@Test
	public void testImageGenerationSuccess() {
		QwenImageClient client = newClient();
		ImageResponse resp = client.generate(ImageRequest.builder()
			.model(QwenModels.WANX_V1).prompt("一只猫").size("1024x1024").build());
		assertEquals(IMAGE_URL, resp.firstUrl());
		assertEquals(1, resp.data().size());
		assertTrue(resp.rawJson().contains("SUCCEEDED"));
		assertEquals("Bearer " + API_KEY, this.submitHeaderAuth.get());
		assertEquals("enable", this.submitHeaderAsync.get());
		String body = this.submitBody.get();
		assertTrue(body.contains("\"model\":\"wanx-v1\""));
		assertTrue(body.contains("\"prompt\":\"一只猫\""));
		assertTrue("size normalized to 1024*1024", body.contains("\"size\":\"1024*1024\""));
		assertEquals(2, this.pollHits.get());
		client.close();
	}

	/** 失败：轮询返回 FAILED，message 透传到异常。 */
	@Test
	public void testImageGenerationFailed() {
		this.mode = "failed";
		QwenImageClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class,
			() -> client.generate(QwenModels.WANX_V1, "bad prompt"));
		assertTrue(e.getMessage().contains("bad prompt"));
		client.close();
	}

	/** 超时：轮询始终 PENDING，超过最大等待抛 AiTimeoutException。 */
	@Test
	public void testImageGenerationTimeout() {
		this.mode = "pending";
		QwenImageClient.POLL_INTERVAL_MS = 30L;
		QwenImageClient.MAX_WAIT_MS = 150L;
		QwenImageClient client = newClient();
		assertThrows(AiTimeoutException.class,
			() -> client.generate(QwenModels.WANX_V1, "slow"));
		assertTrue(this.pollHits.get() > 1);
		client.close();
	}

	/** 便捷 generate(model, prompt) 与 name()。 */
	@Test
	public void testConvenienceAndName() {
		assertEquals("qwen-image", newClient().name());
		QwenImageClient client = newClient();
		ImageResponse resp = client.generate(QwenModels.WANX_V1, "a dog");
		assertEquals(IMAGE_URL, resp.firstUrl());
		client.close();
	}

	/** Util 静态入口：resetImageClient 将单例容器置空（反射注入→reset→断言未初始化）。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilReset() throws Exception {
		java.lang.reflect.Field f = QwenUtil.class.getDeclaredField("IMAGE");
		f.setAccessible(true);
		SingletonHolder<QwenImageClient> holder =
			(SingletonHolder<QwenImageClient>) f.get(null);
		holder.set(newClient());
		assertTrue(holder.isInitialized());
		QwenUtil.resetImageClient();
		assertFalse(holder.isInitialized());
	}

	/** Models 常量。 */
	@Test
	public void testModelsConstants() {
		assertEquals("wanx-v1", QwenModels.WANX_V1);
		assertEquals("wan2.1-t2i-turbo", QwenModels.WAN2_1_T2I_TURBO);
		assertEquals("wan2.1-t2i-plus", QwenModels.WAN2_1_T2I_PLUS);
		List<String> ids = List.of(QwenModels.WANX_V1, QwenModels.WAN2_1_T2I_TURBO,
			QwenModels.WAN2_1_T2I_PLUS);
		for (String id : ids) {
			assertNotNull(id);
		}
	}
}
