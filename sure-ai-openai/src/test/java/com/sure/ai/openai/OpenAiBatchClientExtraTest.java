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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;

/**
 * {@link OpenAiBatchClient} 边界分支测试：Organization 头、endpoint 覆盖、缺省完成时间窗、
 * 字符串 error 与轮询中断。
 *
 * <p>与 {@link OpenAiBatchClientTest} 相互独立，零真实网络。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class OpenAiBatchClientExtraTest {

	/** 被测批次 ID。 */
	private static final String BATCH_ID = "b1";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> reqBody = new AtomicReference<>();
	private final AtomicReference<String> reqOrg = new AtomicReference<>();
	private String getErrorJson;

	/** 启动本地 mock。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		this.getErrorJson = "";
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if ("POST".equals(exchange.getRequestMethod()) && path.equals("/v1/batches")) {
				this.reqOrg.set(exchange.getRequestHeaders().getFirst("OpenAI-Organization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.reqBody.set(new String(in, StandardCharsets.UTF_8));
				respond(exchange, 200, "{\"id\":\"" + BATCH_ID + "\",\"status\":\"validating\"}");
			}
			else if ("GET".equals(exchange.getRequestMethod()) && path.equals("/v1/batches/" + BATCH_ID)) {
				respond(exchange, 200, "{\"id\":\"" + BATCH_ID + "\",\"status\":\"in_progress\","
					+ "\"request_counts\":{\"total\":1,\"completed\":0,\"failed\":0}" + this.getErrorJson + "}");
			}
			else {
				respond(exchange, 404, "{}");
			}
		});
		OpenAiBatchClient.POLL_INTERVAL_MS = 2000L;
	}

	/** 停止服务并恢复轮询参数。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		OpenAiBatchClient.POLL_INTERVAL_MS = 2000L;
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

	/** 构造指向 mock、可带 organization 的客户端。 */
	private OpenAiBatchClient newClient(String organization) {
		AiConfig.Builder b = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl);
		if (organization != null) {
			b.organization(organization);
		}
		return new OpenAiBatchClient(b.build());
	}

	/** 携带 Organization 时，请求头应带 OpenAI-Organization。 */
	@Test
	public void testCreateWithOrganizationHeader() {
		OpenAiBatchClient client = newClient("org-9");
		BatchResponse resp = client.createBatch(BatchRequest.builder()
			.model("m").inputFileId("f1").build());
		assertEquals(BATCH_ID, resp.id());
		assertEquals("org-9", this.reqOrg.get());
		client.close();
	}

	/** extra.endpoint 覆盖默认 endpoint；缺省 completionWindow 落 24h 默认分支。 */
	@Test
	public void testCreateWithEndpointOverrideAndDefaultWindow() {
		OpenAiBatchClient client = newClient(null);
		BatchResponse resp = client.createBatch(BatchRequest.builder()
			.model("m").inputFileId("f1")
			.extra("endpoint", "/v1/embeddings")
			.build());
		assertEquals(BATCH_ID, resp.id());
		assertTrue(this.reqBody.get().contains("\"endpoint\":\"/v1/embeddings\""));
		assertTrue(this.reqBody.get().contains("\"completion_window\":\"24h\""));
		client.close();
	}

	/** error 为 JSON 字符串时，parseError 走 getAsString 分支。 */
	@Test
	public void testGetBatchErrorAsString() {
		this.getErrorJson = ",\"error\":\"plain-string-error\"";
		OpenAiBatchClient client = newClient(null);
		BatchResponse resp = client.getBatch(BATCH_ID);
		assertEquals("plain-string-error", resp.error());
		client.close();
	}

	/** 轮询睡眠期间线程被中断，sleepQuietly 包装为 AiException。 */
	@Test
	public void testWaitInterrupted() throws Exception {
		OpenAiBatchClient.POLL_INTERVAL_MS = 10000L;
		OpenAiBatchClient client = newClient(null);
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
		AiException e = assertThrows(AiException.class,
			() -> client.waitForCompletion(BATCH_ID, 60000L));
		assertTrue(e.getMessage(), e.getMessage().contains("interrupted"));
		watcher.join();
		Thread.interrupted();
		client.close();
	}
}
