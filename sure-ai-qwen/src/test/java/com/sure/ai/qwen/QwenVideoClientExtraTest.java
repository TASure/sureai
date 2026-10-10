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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.VideoRequest;

/**
 * {@link QwenVideoClient} 边界分支测试：negativePrompt、extraHeader、提交 IO/中断、
 * 无 video_url 响应、轮询睡眠中断。
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenVideoClientExtraTest {

	private HttpServer server;
	private String baseUrl;
	private String pollStatus = "SUCCEEDED";
	private boolean omitVideoUrl;

	/** 启动 mock。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.pollStatus = "SUCCEEDED";
		this.omitVideoUrl = false;
		this.server.createContext("/", this::route);
	}

	/** 停止 mock 并恢复参数。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		QwenVideoClient.POLL_INTERVAL_MS = 2000L;
		QwenVideoClient.MAX_WAIT_MS = 120000L;
	}

	/** 路由提交与轮询。 */
	private void route(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		ex.getRequestBody().readAllBytes();
		String body;
		if (path.endsWith("/video-synthesis")) {
			body = "{\"output\":{\"task_id\":\"task-1\"}}";
		}
		else if (this.omitVideoUrl) {
			body = "{\"output\":{\"task_status\":\"SUCCEEDED\"}}";
		}
		else {
			body = "{\"output\":{\"task_status\":\"" + this.pollStatus + "\",\"video_url\":\"https://example.com/v.mp4\"}}";
		}
		byte[] out = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	/** 构造客户端。 */
	private QwenVideoClient newClient(boolean withExtra) {
		AiConfig.Builder b = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl);
		if (withExtra) {
			b.extraHeader("X-Custom", "iv");
		}
		return new QwenVideoClient(b.build());
	}

	/** negativePrompt 与显式参数进入请求体。 */
	@Test
	public void testNegativePromptAndParams() {
		QwenVideoClient client = newClient(false);
		com.sure.ai.model.VideoResponse resp = client.generate(VideoRequest.builder()
			.model("wan2.1").prompt("猫").negativePrompt("模糊").size("1280x720")
			.duration(5).seed(42).build());
		assertEquals("https://example.com/v.mp4", resp.firstUrl());
		client.close();
	}

	/** extraHeader 携带。 */
	@Test
	public void testExtraHeader() {
		QwenVideoClient client = newClient(true);
		com.sure.ai.model.VideoResponse resp = client.generate(
			VideoRequest.builder().model("wan2.1").prompt("猫").build());
		assertEquals("https://example.com/v.mp4", resp.firstUrl());
		client.close();
	}

	/** 无 video_url 的成功响应 → 空结果列表。 */
	@Test
	public void testSuccessWithoutVideoUrl() {
		this.omitVideoUrl = true;
		QwenVideoClient client = newClient(false);
		com.sure.ai.model.VideoResponse resp = client.generate(
			VideoRequest.builder().model("wan2.1").prompt("猫").build());
		assertEquals(0, resp.data().size());
		client.close();
	}

	/** 提交 IO 失败 → AiTimeoutException。 */
	@Test
	public void testSubmitIoFailure() {
		QwenVideoClient dead = new QwenVideoClient(
			AiConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:9").build());
		assertThrows(AiTimeoutException.class, () -> dead.generate(
			VideoRequest.builder().model("wan2.1").prompt("猫").build()));
		dead.close();
	}

	/** 提交被中断 → AiException(qwen video submit interrupted)。 */
	@Test
	public void testSubmitInterrupted() throws Exception {
		this.server.removeContext("/");
		this.server.createContext("/", ex -> {
			try {
				Thread.sleep(2000L);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			byte[] out = "{\"output\":{\"task_id\":\"t\"}}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, out.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(out);
			}
		});
		QwenVideoClient client = newClient(false);
		Thread caller = Thread.currentThread();
		Thread watcher = new Thread(() -> {
			try {
				Thread.sleep(200L);
				caller.interrupt();
			}
			catch (InterruptedException ignored) {
			}
		});
		watcher.start();
		AiException e = assertThrows(AiException.class, () -> client.generate(
			VideoRequest.builder().model("wan2.1").prompt("猫").build()));
		assertTrue(e.getMessage(), e.getMessage().contains("qwen video submit interrupted"));
		watcher.join();
		Thread.interrupted();
		client.close();
	}

	/** 轮询睡眠期间被中断 → AiException(qwen video polling interrupted)。 */
	@Test
	public void testPollSleepInterrupted() throws Exception {
		this.pollStatus = "PENDING";
		QwenVideoClient.POLL_INTERVAL_MS = 10000L;
		QwenVideoClient.MAX_WAIT_MS = 60000L;
		QwenVideoClient client = newClient(false);
		Thread caller = Thread.currentThread();
		Thread watcher = new Thread(() -> {
			try {
				Thread.sleep(800L);
				caller.interrupt();
			}
			catch (InterruptedException ignored) {
			}
		});
		watcher.start();
		AiException e = assertThrows(AiException.class, () -> client.generate(
			VideoRequest.builder().model("wan2.1").prompt("猫").build()));
		assertTrue(e.getMessage(), e.getMessage().contains("qwen video polling interrupted"));
		watcher.join();
		Thread.interrupted();
		client.close();
	}
}
