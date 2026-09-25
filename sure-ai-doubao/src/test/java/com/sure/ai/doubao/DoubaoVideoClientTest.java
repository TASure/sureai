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

package com.sure.ai.doubao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * {@link DoubaoVideoClient} 测试：本地 HttpServer mock 提交与轮询接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class DoubaoVideoClientTest {

	private static final String API_KEY = "ark-key";
	private static final String VIDEO_URL = "https://example.com/seedance.mp4";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> submitPath = new AtomicReference<>();
	private final AtomicReference<String> pollPath = new AtomicReference<>();
	private final AtomicReference<String> submitBody = new AtomicReference<>();
	private final AtomicInteger pollHits = new AtomicInteger();

	/** 失败模式：null=成功，"failed"=任务失败，"pending"=一直运行中。 */
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
		DoubaoVideoClient.POLL_INTERVAL_MS = 2000L;
		DoubaoVideoClient.MAX_WAIT_MS = 120000L;
		DoubaoUtil.resetVideoClient();
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			if (path.endsWith("/contents/generations/tasks") && "POST".equals(exchange.getRequestMethod())) {
				this.submitPath.set(path);
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.submitBody.set(new String(in, StandardCharsets.UTF_8));
				respond(exchange, 200, "{\"id\":\"cgt-1\",\"status\":\"queued\","
					+ "\"model\":\"doubao-seedance-2-5-260628\"}");
				return;
			}
			if (path.contains("/contents/generations/tasks/")) {
				this.pollPath.set(path);
				int hits = this.pollHits.incrementAndGet();
				if ("failed".equals(this.mode)) {
					respond(exchange, 200, "{\"id\":\"cgt-1\",\"status\":\"failed\"}");
					return;
				}
				if ("pending".equals(this.mode) || hits == 1) {
					respond(exchange, 200, "{\"id\":\"cgt-1\",\"status\":\"running\"}");
					return;
				}
				respond(exchange, 200, "{\"id\":\"cgt-1\",\"status\":\"succeeded\","
					+ "\"content\":{\"video_url\":\"" + VIDEO_URL + "\",\"last_frame_url\":null}}");
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
	private DoubaoVideoClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new DoubaoVideoClient(cfg);
	}

	/** 成功：Bearer 头、提交/轮询路径、content 结构、video_url 正确。 */
	@Test
	public void testSuccess() {
		DoubaoVideoClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.builder()
			.model(DoubaoModels.SEEDANCE_2_5).prompt("海浪拍岸")
			.ratio("16:9").duration(5).resolution("720p")
			.withAudio(true).seed(123).build());
		assertEquals("Bearer " + API_KEY, this.lastAuth.get());
		assertTrue(this.submitPath.get().endsWith("/api/v3/contents/generations/tasks"));
		assertTrue(this.pollPath.get().endsWith("/api/v3/contents/generations/tasks/cgt-1"));
		String body = this.submitBody.get();
		assertTrue(body.contains("\"model\":\"doubao-seedance-2-5-260628\""));
		assertTrue(body.contains("\"type\":\"text\""));
		assertTrue(body.contains("海浪拍岸"));
		assertTrue(body.contains("\"ratio\":\"16:9\""));
		assertTrue(body.contains("\"resolution\":\"720p\""));
		assertTrue(body.contains("\"seed\":123"));
		assertEquals(VIDEO_URL, resp.firstUrl());
		assertEquals(2, this.pollHits.get());
		client.close();
	}

	/** failed 状态抛 AiException。 */
	@Test
	public void testFailed() {
		this.mode = "failed";
		DoubaoVideoClient client = newClient();
		assertThrows(AiException.class,
			() -> client.generate(VideoRequest.of(DoubaoModels.SEEDANCE_2_5, "x")));
		client.close();
	}

	/** 超时：始终 running，超过最大等待抛 AiTimeoutException。 */
	@Test
	public void testTimeout() {
		this.mode = "pending";
		DoubaoVideoClient.POLL_INTERVAL_MS = 30L;
		DoubaoVideoClient.MAX_WAIT_MS = 150L;
		DoubaoVideoClient client = newClient();
		assertThrows(AiTimeoutException.class,
			() -> client.generate(VideoRequest.of(DoubaoModels.SEEDANCE_2_5, "slow")));
		assertTrue(this.pollHits.get() > 1);
		client.close();
	}

	/** name() 与默认 baseUrl。 */
	@Test
	public void testNameAndDefaults() {
		assertEquals("doubao-video", newClient().name());
		assertEquals("https://ark.cn-beijing.volces.com", DoubaoVideoClient.DEFAULT_BASE_URL);
	}

	/** Models 视频常量。 */
	@Test
	public void testVideoModelConstants() {
		assertEquals("doubao-seedance-2-5-260628", DoubaoModels.SEEDANCE_2_5);
		List<String> ids = List.of(DoubaoModels.SEEDANCE_2_5, DoubaoModels.SEEDANCE_2_0,
			DoubaoModels.SEED_TTS_2_0, DoubaoModels.DOUBAO_TTS_SPEAKER_DEFAULT,
			DoubaoModels.VOLC_BIGASR_AUC);
		for (String id : ids) {
			assertFalse(id.isEmpty());
		}
	}

	/** Util：resetVideoClient 将单例字段置 null（反射注入→reset→断言为 null）。 */
	@Test
	public void testUtilReset() throws Exception {
		java.lang.reflect.Field f = DoubaoUtil.class.getDeclaredField("videoClient");
		f.setAccessible(true);
		f.set(null, newClient());
		assertNotNull(f.get(null));
		DoubaoUtil.resetVideoClient();
		assertNull(f.get(null));
	}
}
