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
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * {@link QwenVideoClient} 测试：本地 HttpServer mock 提交与轮询接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenVideoClientTest {

	private static final String API_KEY = "test-key";
	private static final String VIDEO_URL = "https://example.com/result.mp4";

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
		QwenVideoClient.POLL_INTERVAL_MS = 2000L;
		QwenVideoClient.MAX_WAIT_MS = 120000L;
		QwenUtil.resetVideoClient();
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/api/v1/services/aigc/video-generation/video-synthesis")) {
				this.submitHeaderAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				this.submitHeaderAsync.set(exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.submitBody.set(new String(in, StandardCharsets.UTF_8));
				String body = "{\"output\":{\"task_id\":\"task-v\",\"task_status\":\"PENDING\"},"
					+ "\"request_id\":\"req-1\"}";
				respond(exchange, 200, body);
				return;
			}
			if (path.equals("/api/v1/tasks/task-v")) {
				int hits = this.pollHits.incrementAndGet();
				if ("failed".equals(this.mode)) {
					String body = "{\"output\":{\"task_status\":\"FAILED\",\"message\":\"bad prompt\"},"
						+ "\"request_id\":\"req-2\"}";
					respond(exchange, 200, body);
					return;
				}
				if ("pending".equals(this.mode) || hits == 1) {
					String body = "{\"output\":{\"task_status\":\"RUNNING\"},\"request_id\":\"req-2\"}";
					respond(exchange, 200, body);
					return;
				}
				String body = "{\"output\":{\"task_id\":\"task-v\",\"task_status\":\"SUCCEEDED\","
					+ "\"video_url\":\"" + VIDEO_URL + "\"},\"request_id\":\"req-3\","
					+ "\"usage\":{\"video_count\":1}}";
				respond(exchange, 200, body);
				return;
			}
			respond(exchange, 404, "{}");
		});
	}

	/** 发送响应。 */
	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 构造指向 mock 的客户端。 */
	private QwenVideoClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new QwenVideoClient(cfg);
	}

	/** 成功：提交头正确，size 归一化，轮询两次后返回视频 URL。 */
	@Test
	public void testVideoGenerationSuccess() {
		QwenVideoClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.builder()
			.model(QwenModels.WAN2_6_T2V).prompt("海浪拍岸").size("1280x720")
			.duration(10).seed(123).build());
		assertEquals(VIDEO_URL, resp.firstUrl());
		assertEquals(1, resp.data().size());
		assertTrue(resp.rawJson().contains("SUCCEEDED"));
		assertEquals("Bearer " + API_KEY, this.submitHeaderAuth.get());
		assertEquals("enable", this.submitHeaderAsync.get());
		String body = this.submitBody.get();
		assertTrue(body.contains("\"model\":\"wan2.6-t2v\""));
		assertTrue(body.contains("\"prompt\":\"海浪拍岸\""));
		assertTrue("size normalized to 1280*720", body.contains("\"size\":\"1280*720\""));
		assertTrue(body.contains("\"duration\":10"));
		assertTrue(body.contains("\"seed\":123"));
		assertEquals(2, this.pollHits.get());
		client.close();
	}

	/** 失败：轮询返回 FAILED，message 透传到异常。 */
	@Test
	public void testVideoGenerationFailed() {
		this.mode = "failed";
		QwenVideoClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class,
			() -> client.generate(VideoRequest.of(QwenModels.WAN2_6_T2V, "bad prompt")));
		assertTrue(e.getMessage().contains("bad prompt"));
		client.close();
	}

	/** 超时：轮询始终 RUNNING，超过最大等待抛 AiTimeoutException。 */
	@Test
	public void testVideoGenerationTimeout() {
		this.mode = "pending";
		QwenVideoClient.POLL_INTERVAL_MS = 30L;
		QwenVideoClient.MAX_WAIT_MS = 150L;
		QwenVideoClient client = newClient();
		assertThrows(AiTimeoutException.class,
			() -> client.generate(VideoRequest.of(QwenModels.WAN2_6_T2V, "slow")));
		assertTrue(this.pollHits.get() > 1);
		client.close();
	}

	/** 便捷 VideoRequest.of(model, prompt) 与 name()。 */
	@Test
	public void testConvenienceAndName() {
		assertEquals("qwen-video", newClient().name());
		QwenVideoClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.of(QwenModels.WAN2_6_T2V, "a dog running"));
		assertEquals(VIDEO_URL, resp.firstUrl());
		client.close();
	}

	/** Util 静态入口：videoClient/resetVideoClient。 */
	@Test
	public void testUtilEntry() {
		QwenUtil.resetVideoClient();
		assertNotNull(QwenModels.WAN2_6_T2V);
		assertNotNull(QwenModels.WAN2_5_T2V);
		assertNotNull(QwenModels.WANX2_1_T2V);
		List<String> ids = List.of(QwenModels.WAN2_6_T2V, QwenModels.WAN2_5_T2V,
			QwenModels.WANX2_1_T2V);
		for (String id : ids) {
			assertNotNull(id);
		}
	}
}
