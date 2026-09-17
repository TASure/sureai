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

package com.sure.ai.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

/**
 * {@link OpenAiBatchClient} 测试：本地 HttpServer mock batches 提交/查询/轮询。
 *
 * @author sureai
 * @since 0.2.0
 */
public class OpenAiBatchClientTest {

	private static final String API_KEY = "test-key";
	private static final String BATCH_ID = "batch_abc123";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> reqBody = new AtomicReference<>();
	private final AtomicReference<String> reqAuth = new AtomicReference<>();
	private final AtomicReference<String> reqPath = new AtomicReference<>();
	private final AtomicInteger pollHits = new AtomicInteger();

	/** 终态模式：null=第二次 completed，"failed"=失败，"pending"=一直 in_progress。 */
	private String mode;

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		this.pollHits.set(0);
		this.mode = null;
		registerHandlers();
		OpenAiBatchClient.POLL_INTERVAL_MS = 2000L;
	}

	/** 停止服务并恢复轮询参数。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		OpenAiBatchClient.POLL_INTERVAL_MS = 2000L;
		OpenAiUtil.resetBatchClient();
	}

	/** 注册 batches 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String method = exchange.getRequestMethod();
			if (method.equals("POST") && path.equals("/v1/batches")) {
				this.reqAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.reqBody.set(new String(in, StandardCharsets.UTF_8));
				String body = "{\"id\":\"" + BATCH_ID + "\",\"object\":\"batch\",\"status\":\"validating\","
					+ "\"created_at\":1711471649,\"completed_at\":null,"
					+ "\"request_counts\":{\"total\":100,\"completed\":0,\"failed\":0}}";
				respond(exchange, 200, body);
				return;
			}
			if (method.equals("GET") && path.equals("/v1/batches/" + BATCH_ID)) {
				this.reqPath.set(path);
				int hits = this.pollHits.incrementAndGet();
				String status;
				if ("failed".equals(this.mode)) {
					status = "failed";
				} else if ("pending".equals(this.mode) || hits == 1) {
					status = "in_progress";
				} else {
					status = "completed";
				}
				String error = "failed".equals(this.mode)
					? ",\"error\":{\"message\":\"job errored\"}" : "";
				String body = "{\"id\":\"" + BATCH_ID + "\",\"object\":\"batch\",\"status\":\"" + status
					+ "\",\"created_at\":1711471649,\"completed_at\":1711479999,"
					+ "\"request_counts\":{\"total\":100,\"completed\":100,\"failed\":0}" + error + "}";
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
	private OpenAiBatchClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new OpenAiBatchClient(cfg);
	}

	/** 构造带 inputFileId 的请求。 */
	private static BatchRequest sampleRequest() {
		return BatchRequest.builder()
			.model("gpt-4o-mini")
			.inputFileId("file-abc123")
			.completionWindow("24h")
			.metadata("customer_id", "cus-1")
			.build();
	}

	/** 提交：断言请求体 input_file_id/endpoint/completion_window 与鉴权头。 */
	@Test
	public void testBatchCreate() {
		OpenAiBatchClient client = newClient();
		BatchResponse resp = client.createBatch(sampleRequest());
		assertEquals(BATCH_ID, resp.id());
		assertEquals("validating", resp.status());
		assertEquals(100, resp.requestCounts().total());
		assertEquals("Bearer " + API_KEY, this.reqAuth.get());
		String body = this.reqBody.get();
		assertTrue(body.contains("\"input_file_id\":\"file-abc123\""));
		assertTrue(body.contains("\"endpoint\":\"/v1/chat/completions\""));
		assertTrue(body.contains("\"completion_window\":\"24h\""));
		assertTrue(body.contains("\"customer_id\":\"cus-1\""));
		client.close();
	}

	/** 查询：断言 status/request_counts。 */
	@Test
	public void testBatchGet() {
		OpenAiBatchClient client = newClient();
		BatchResponse resp = client.getBatch(BATCH_ID);
		assertEquals(BATCH_ID, resp.id());
		assertEquals("in_progress", resp.status());
		assertEquals(1711479999L, resp.completedAt());
		assertEquals(100, resp.requestCounts().total());
		assertEquals(100, resp.requestCounts().completed());
		assertEquals(0, resp.requestCounts().failed());
		assertEquals("/v1/batches/" + BATCH_ID, this.reqPath.get());
		client.close();
	}

	/** 轮询：in_progress → completed，两次查询后返回。 */
	@Test
	public void testBatchWaitForCompletion() {
		OpenAiBatchClient.POLL_INTERVAL_MS = 30L;
		OpenAiBatchClient client = newClient();
		BatchResponse resp = client.waitForCompletion(BATCH_ID, 5000L);
		assertTrue(resp.isCompleted());
		assertEquals("completed", resp.status());
		assertEquals(2, this.pollHits.get());
		client.close();
	}

	/** 轮询失败：failed 状态抛 AiException。 */
	@Test
	public void testBatchWaitFailed() {
		this.mode = "failed";
		OpenAiBatchClient.POLL_INTERVAL_MS = 30L;
		OpenAiBatchClient client = newClient();
		AiException e = assertThrows(AiException.class,
			() -> client.waitForCompletion(BATCH_ID, 5000L));
		assertTrue(e.getMessage().contains("job errored"));
		client.close();
	}

	/** 轮询超时：始终 in_progress 抛 AiTimeoutException。 */
	@Test
	public void testBatchWaitTimeout() {
		this.mode = "pending";
		OpenAiBatchClient.POLL_INTERVAL_MS = 30L;
		OpenAiBatchClient client = newClient();
		assertThrows(AiTimeoutException.class,
			() -> client.waitForCompletion(BATCH_ID, 150L));
		assertTrue(this.pollHits.get() > 1);
		client.close();
	}

	/** 只传 requests 未传 inputFileId：抛 AiException 提示先上传 JSONL。 */
	@Test
	public void testBatchRequiresInputFileId() {
		OpenAiBatchClient client = newClient();
		BatchRequest req = BatchRequest.builder()
			.model("gpt-4o-mini")
			.addRequest(ChatRequest.builder().model("gpt-4o-mini")
				.messages(ChatMessage.user("hi")).build())
			.build();
		AiException e = assertThrows(AiException.class, () -> client.createBatch(req));
		assertTrue(e.getMessage().contains("input_file_id"));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("openai-batch", newClient().name());
	}

	/** Util 单例 reset/不触网。 */
	@Test
	public void testUtilReset() {
		OpenAiUtil.resetBatchClient();
		assertNotNull(OpenAiModels.GPT_4O);
	}
}
