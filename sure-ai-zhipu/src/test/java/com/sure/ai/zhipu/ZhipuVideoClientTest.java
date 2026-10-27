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
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * {@link ZhipuVideoClient} 测试：本地 HttpServer mock 提交与轮询接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class ZhipuVideoClientTest {

	private static final String API_KEY = "test-id.secure-secret";
	private static final String VIDEO_URL = "https://example.com/cogvideo.mp4";
	private static final String COVER_URL = "https://example.com/cogvideo.jpg";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> submitPath = new AtomicReference<>();
	private final AtomicReference<String> pollPath = new AtomicReference<>();
	private final AtomicReference<String> submitBody = new AtomicReference<>();
	private final AtomicInteger pollHits = new AtomicInteger();

	/** 失败模式：null=成功，"fail"=任务失败，"pending"=一直处理中。 */
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
		ZhipuVideoClient.POLL_INTERVAL_MS = 2000L;
		ZhipuVideoClient.MAX_WAIT_MS = 120000L;
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			if (path.endsWith("/videos/generations")) {
				this.submitPath.set(path);
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.submitBody.set(new String(in, StandardCharsets.UTF_8));
				respond(exchange, 200, "{\"id\":\"task-1\",\"task_status\":\"PROCESSING\","
					+ "\"model\":\"cogvideox-3\"}");
				return;
			}
			if (path.contains("/async-result/")) {
				this.pollPath.set(path);
				int hits = this.pollHits.incrementAndGet();
				if ("fail".equals(this.mode)) {
					respond(exchange, 200, "{\"id\":\"task-1\",\"task_status\":\"FAIL\"}");
					return;
				}
				if ("pending".equals(this.mode) || hits == 1) {
					respond(exchange, 200, "{\"id\":\"task-1\",\"task_status\":\"PROCESSING\"}");
					return;
				}
				respond(exchange, 200, "{\"id\":\"task-1\",\"task_status\":\"SUCCESS\",\"created\":123,"
					+ "\"video_result\":[{\"url\":\"" + VIDEO_URL + "\","
					+ "\"cover_image_url\":\"" + COVER_URL + "\"}]}");
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
	private ZhipuVideoClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new ZhipuVideoClient(cfg);
	}

	/** 成功：JWT Bearer、提交/轮询路径、firstUrl/firstCoverUrl 正确。 */
	@Test
	public void testSuccess() {
		ZhipuVideoClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.builder()
			.model(ZhipuModels.COGVIDEOX_3).prompt("城市夜景")
			.size("1920x1080").duration(5).withAudio(false).build());
		String auth = this.lastAuth.get();
		assertTrue(auth.startsWith("Bearer "));
		assertFalse("must not be raw apiKey", auth.equals("Bearer " + API_KEY));
		assertEquals(3, auth.substring("Bearer ".length()).split("\\.").length);
		assertTrue(this.submitPath.get().endsWith("/api/paas/v4/videos/generations"));
		assertTrue(this.pollPath.get().endsWith("/api/paas/v4/async-result/task-1"));
		assertTrue(this.submitBody.get().contains("\"model\":\"cogvideox-3\""));
		assertTrue(this.submitBody.get().contains("\"prompt\":\"城市夜景\""));
		assertTrue(this.submitBody.get().contains("\"size\":\"1920x1080\""));
		assertEquals(VIDEO_URL, resp.firstUrl());
		assertEquals(COVER_URL, resp.firstCoverUrl());
		assertEquals(123L, resp.created());
		assertEquals(2, this.pollHits.get());
		client.close();
	}

	/** FAIL 状态抛 AiException。 */
	@Test
	public void testFail() {
		this.mode = "fail";
		ZhipuVideoClient client = newClient();
		assertThrows(AiException.class,
			() -> client.generate(VideoRequest.of(ZhipuModels.COGVIDEOX_3, "x")));
		client.close();
	}

	/** 超时：始终 PROCESSING，超过最大等待抛 AiTimeoutException。 */
	@Test
	public void testTimeout() {
		this.mode = "pending";
		ZhipuVideoClient.POLL_INTERVAL_MS = 30L;
		ZhipuVideoClient.MAX_WAIT_MS = 600L;
		ZhipuVideoClient client = newClient();
		assertThrows(AiTimeoutException.class,
			() -> client.generate(VideoRequest.of(ZhipuModels.COGVIDEOX_3, "slow")));
		assertTrue("应多次轮询后才超时，实际轮询次数: " + this.pollHits.get(), this.pollHits.get() > 1);
		client.close();
	}

	/** name() 与默认 baseUrl。 */
	@Test
	public void testNameAndDefaults() {
		assertEquals("zhipu-video", newClient().name());
		assertEquals("https://open.bigmodel.cn", ZhipuVideoClient.DEFAULT_BASE_URL);
	}

	/** Models 视频常量。 */
	@Test
	public void testVideoModelConstants() {
		assertEquals("cogvideox-3", ZhipuModels.COGVIDEOX_3);
		List<String> ids = List.of(ZhipuModels.COGVIDEOX_3, ZhipuModels.COGVIDEOX_2,
			ZhipuModels.COGVIDEOX_FLASH, ZhipuModels.GLM_TTS, ZhipuModels.GLM_ASR_2512);
		for (String id : ids) {
			assertFalse(id.isEmpty());
		}
	}
}
