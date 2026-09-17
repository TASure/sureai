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
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;

/**
 * {@link ZhipuBatchClient} 测试：本地 HttpServer mock，断言 JWT 鉴权与 /batches 路径。
 *
 * @author sureai
 * @since 0.2.0
 */
public class ZhipuBatchClientTest {

	private static final String API_KEY = "testid.testsecret";
	private static final String BATCH_ID = "batch_zhipu_1";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> reqBody = new AtomicReference<>();
	private final AtomicReference<String> reqAuth = new AtomicReference<>();
	private final AtomicReference<String> reqPath = new AtomicReference<>();
	private final AtomicInteger pollHits = new AtomicInteger();

	/** GET 响应模式：null=completed，"failed"=失败(error 对象)，"errstr"=completed+error 字符串，"pending"=一直 in_progress。 */
	private String mode;

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/api/paas/v4";
		this.pollHits.set(0);
		this.mode = null;
		registerHandlers();
	}

	/** 停止服务并清理单例。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		ZhipuUtil.resetBatchClient();
	}

	/** 注册 batches 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String method = exchange.getRequestMethod();
			if (method.equals("POST") && path.equals("/api/paas/v4/batches")) {
				this.reqAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.reqBody.set(new String(in, StandardCharsets.UTF_8));
				String body = "{\"id\":\"" + BATCH_ID + "\",\"status\":\"validating\","
					+ "\"created_at\":1711471649,\"completed_at\":null,"
					+ "\"request_counts\":{\"total\":20,\"completed\":0,\"failed\":0}}";
				respond(exchange, 200, body);
				return;
			}
			if (method.equals("GET") && path.equals("/api/paas/v4/batches/" + BATCH_ID)) {
				this.reqPath.set(path);
				int hits = this.pollHits.incrementAndGet();
				String status;
				String error;
				if ("failed".equals(this.mode)) {
					status = "failed";
					error = ",\"error\":{\"message\":\"job errored\"}";
				} else if ("errstr".equals(this.mode)) {
					status = "completed";
					error = ",\"error\":\"plain string error\"";
				} else if ("pending".equals(this.mode) || hits == 1) {
					status = "in_progress";
					error = "";
				} else {
					status = "completed";
					error = "";
				}
				String body = "{\"id\":\"" + BATCH_ID + "\",\"status\":\"" + status
					+ "\",\"created_at\":1711471649,\"completed_at\":1711479999,"
					+ "\"request_counts\":{\"total\":20,\"completed\":20,\"failed\":0}" + error + "}";
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
	private ZhipuBatchClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new ZhipuBatchClient(cfg);
	}

	/** 提交：断言 JWT Bearer 鉴权、/batches 路径与请求体。 */
	@Test
	public void testBatchCreate() {
		ZhipuBatchClient client = newClient();
		BatchResponse resp = client.createBatch(BatchRequest.builder()
			.model("glm-4").inputFileId("file-zhipu-1").completionWindow("24h").build());
		assertEquals(BATCH_ID, resp.id());
		assertEquals("validating", resp.status());
		String auth = this.reqAuth.get();
		assertNotNull(auth);
		assertTrue("JWT 应以 Bearer 开头", auth.startsWith("Bearer eyJ"));
		assertEquals("JWT 三段式", 3, auth.split("\\.").length);
		String body = this.reqBody.get();
		assertTrue(body.contains("\"input_file_id\":\"file-zhipu-1\""));
		assertTrue(body.contains("\"endpoint\":\"/v1/chat/completions\""));
		assertTrue(body.contains("\"completion_window\":\"24h\""));
		client.close();
	}

	/** 查询：断言路径与 status/request_counts。 */
	@Test
	public void testBatchGet() {
		this.pollHits.set(1);
		ZhipuBatchClient client = newClient();
		BatchResponse resp = client.getBatch(BATCH_ID);
		assertEquals(BATCH_ID, resp.id());
		assertTrue(resp.isCompleted());
		assertEquals(20, resp.requestCounts().total());
		assertEquals(20, resp.requestCounts().completed());
		assertEquals("/api/paas/v4/batches/" + BATCH_ID, this.reqPath.get());
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("zhipu-batch", newClient().name());
	}

	/** 轮询：in_progress → completed。 */
	@Test
	public void testWaitForCompletion() {
		ZhipuBatchClient.POLL_INTERVAL_MS = 30L;
		ZhipuBatchClient client = newClient();
		BatchResponse resp = client.waitForCompletion(BATCH_ID, 5000L);
		assertTrue(resp.isCompleted());
		assertEquals(2, this.pollHits.get());
		client.close();
	}

	/** 轮询失败：failed 状态带 error 对象，抛 AiException 并透传 message。 */
	@Test
	public void testWaitFailed() {
		this.mode = "failed";
		ZhipuBatchClient.POLL_INTERVAL_MS = 30L;
		ZhipuBatchClient client = newClient();
		AiException e = assertThrows(AiException.class,
			() -> client.waitForCompletion(BATCH_ID, 5000L));
		assertTrue(e.getMessage().contains("job errored"));
		client.close();
	}

	/** 轮询超时：始终 in_progress 抛 AiTimeoutException。 */
	@Test
	public void testWaitTimeout() {
		this.mode = "pending";
		ZhipuBatchClient.POLL_INTERVAL_MS = 30L;
		ZhipuBatchClient client = newClient();
		assertThrows(AiTimeoutException.class,
			() -> client.waitForCompletion(BATCH_ID, 150L));
		assertTrue(this.pollHits.get() > 1);
		client.close();
	}

	/** error 为 JSON 对象：解析其 message 字段。 */
	@Test
	public void testGetErrorObject() {
		this.mode = "failed";
		ZhipuBatchClient client = newClient();
		BatchResponse resp = client.getBatch(BATCH_ID);
		assertEquals("failed", resp.status());
		assertEquals("job errored", resp.error());
		client.close();
	}

	/** error 为 JSON 字符串：原样返回。 */
	@Test
	public void testGetErrorString() {
		this.mode = "errstr";
		ZhipuBatchClient client = newClient();
		BatchResponse resp = client.getBatch(BATCH_ID);
		assertEquals("plain string error", resp.error());
		client.close();
	}

	/** GET 无 error 字段：error 为 null。 */
	@Test
	public void testGetNoError() {
		ZhipuBatchClient client = newClient();
		BatchResponse resp = client.getBatch(BATCH_ID);
		assertNull(resp.error());
		client.close();
	}

	/** extra.endpoint 覆盖缺省 endpoint。 */
	@Test
	public void testEndpointOverride() {
		ZhipuBatchClient client = newClient();
		client.createBatch(BatchRequest.builder()
			.model("glm-4").inputFileId("file-zhipu-1")
			.extra("endpoint", "/v1/embeddings").build());
		assertTrue(this.reqBody.get().contains("\"endpoint\":\"/v1/embeddings\""));
		client.close();
	}

	/** baseUrl 为空时补默认地址（仅构造，不触网）。 */
	@Test
	public void testDefaultBaseUrl() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).build();
		ZhipuBatchClient client = new ZhipuBatchClient(cfg);
		assertEquals("zhipu-batch", client.name());
		client.close();
	}
}
