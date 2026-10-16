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
import com.sure.ai.model.ImageRequest;

/**
 * {@link QwenImageClient} 边界分支测试：n/style 显式值、extraHeader、提交 IO/中断、轮询睡眠中断。
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenImageClientExtraTest {

	private HttpServer server;
	private String baseUrl;
	private boolean alwaysPending;

	/** 启动 mock。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.alwaysPending = false;
		this.server.createContext("/", this::route);
	}

	/** 停止 mock 并恢复参数。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		QwenImageClient.POLL_INTERVAL_MS = 2000L;
		QwenImageClient.MAX_WAIT_MS = 120000L;
	}

	/** 路由提交与轮询。 */
	private void route(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		ex.getRequestBody().readAllBytes();
		String body;
		if (path.endsWith("/image-synthesis")) {
			body = "{\"output\":{\"task_id\":\"task-1\"}}";
		}
		else {
			String status = this.alwaysPending ? "PENDING" : "SUCCEEDED";
			body = "{\"output\":{\"task_status\":\"" + status + "\",\"results\":[{\"url\":\"https://example.com/i.png\"}]}}";
		}
		byte[] out = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	/** 构造客户端，可带 extraHeader。 */
	private QwenImageClient newClient(boolean withExtra) {
		AiConfig.Builder b = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl);
		if (withExtra) {
			b.extraHeader("X-Custom", "iv");
		}
		return new QwenImageClient(b.build());
	}

	/** 显式 n 与 style 进入请求体。 */
	@Test
	public void testExplicitNAndStyle() {
		QwenImageClient client = newClient(false);
		com.sure.ai.model.ImageResponse resp = client.generate(ImageRequest.builder()
			.model("wanx").prompt("猫").n(2).style("vivid").size("1024x1024").build());
		assertEquals("https://example.com/i.png", resp.firstUrl());
		client.close();
	}

	/** 携带 extraHeader。 */
	@Test
	public void testExtraHeader() {
		QwenImageClient client = newClient(true);
		com.sure.ai.model.ImageResponse resp = client.generate(
			ImageRequest.builder().model("wanx").prompt("猫").build());
		assertEquals("https://example.com/i.png", resp.firstUrl());
		client.close();
	}

	/** 提交 IO 失败 → AiTimeoutException。 */
	@Test
	public void testSubmitIoFailure() {
		QwenImageClient dead = new QwenImageClient(
			AiConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:9").build());
		assertThrows(AiTimeoutException.class, () -> dead.generate(
			ImageRequest.builder().model("wanx").prompt("猫").build()));
		dead.close();
	}

	/** 提交被中断 → AiException(qwen image submit interrupted)。 */
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
		QwenImageClient client = newClient(false);
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
			ImageRequest.builder().model("wanx").prompt("猫").build()));
		assertTrue(e.getMessage(), e.getMessage().contains("qwen image submit interrupted"));
		watcher.join();
		Thread.interrupted();
		client.close();
	}

	/** 轮询睡眠期间被中断 → AiException(qwen image polling interrupted)。 */
	@Test
	public void testPollSleepInterrupted() throws Exception {
		this.alwaysPending = true;
		QwenImageClient.POLL_INTERVAL_MS = 10000L;
		QwenImageClient.MAX_WAIT_MS = 60000L;
		QwenImageClient client = newClient(false);
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
			ImageRequest.builder().model("wanx").prompt("猫").build()));
		assertTrue(e.getMessage(), e.getMessage().contains("qwen image polling interrupted"));
		watcher.join();
		Thread.interrupted();
		client.close();
	}
}
