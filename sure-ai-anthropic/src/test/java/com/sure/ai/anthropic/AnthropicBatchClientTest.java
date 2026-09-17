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

package com.sure.ai.anthropic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

/**
 * {@link AnthropicBatchClient} 集成测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.2.0
 */
public class AnthropicBatchClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastMethod = new AtomicReference<>();
	private final AtomicReference<String> lastPath = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastApiKey = new AtomicReference<>();
	private final AtomicInteger getHits = new AtomicInteger();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port;
		this.lastMethod.set(null);
		this.lastPath.set(null);
		this.lastBody.set(null);
		this.lastApiKey.set(null);
		this.getHits.set(0);
		registerHandlers();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private AnthropicBatchClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("batch-key").baseUrl(this.baseUrl).build();
		return new AnthropicBatchClient(cfg);
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/v1/messages/batches", exchange -> {
			this.lastMethod.set(exchange.getRequestMethod());
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
			if ("POST".equals(exchange.getRequestMethod())) {
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.lastBody.set(new String(in, StandardCharsets.UTF_8));
				String body = "{\"id\":\"batch_1\",\"type\":\"message_batch\","
					+ "\"processing_status\":\"in_progress\",\"request_format\":\"messages\","
					+ "\"request_counts\":{\"processing\":1,\"succeeded\":0,\"errored\":0}}";
				respond(exchange, 200, body);
				return;
			}
			if ("GET".equals(exchange.getRequestMethod())) {
				int hit = this.getHits.incrementAndGet();
				String status = hit == 1 ? "in_progress" : "ended";
				int succeeded = hit == 1 ? 0 : 1;
				String body = "{\"id\":\"batch_1\",\"type\":\"message_batch\","
					+ "\"processing_status\":\"" + status + "\","
					+ "\"request_counts\":{\"processing\":" + (hit == 1 ? 1 : 0)
					+ ",\"succeeded\":" + succeeded + ",\"errored\":0}}";
				respond(exchange, 200, body);
			}
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

	/** createBatch：POST 构造 requests[].custom_id/params。 */
	@Test
	public void testBatchCreate() {
		AnthropicBatchClient client = newClient();
		BatchRequest req = BatchRequest.builder().model("claude-haiku-4-5")
			.addRequest(ChatRequest.builder().model("claude-haiku-4-5")
				.messages(ChatMessage.user("hi")).build())
			.build();
		BatchResponse resp = client.createBatch(req);
		assertEquals("POST", this.lastMethod.get());
		assertTrue(this.lastPath.get().endsWith("/v1/messages/batches"));
		assertEquals("batch_1", resp.id());
		assertEquals("in_progress", resp.status());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"custom_id\""));
		assertTrue(body.contains("\"params\""));
		assertTrue(body.contains("\"max_tokens\""));
		assertTrue(body.contains("claude-haiku-4-5"));
		assertTrue(body.contains("\"messages\""));
		assertEquals("batch-key", this.lastApiKey.get());
		client.close();
	}

	/** getBatch：解析 processing_status 与 request_counts。 */
	@Test
	public void testBatchGet() {
		AnthropicBatchClient client = newClient();
		BatchResponse resp = client.getBatch("batch_1");
		assertEquals("batch_1", resp.id());
		assertEquals("in_progress", resp.status());
		assertNotNull(resp.requestCounts());
		assertEquals(1, resp.requestCounts().total());
		assertEquals(0, resp.requestCounts().completed());
		client.close();
	}

	/** waitForCompletion：首次 in_progress，第二次 ended，轮询返回终态。 */
	@Test
	public void testWaitForCompletion() {
		AnthropicBatchClient client = newClient();
		BatchResponse resp = client.waitForCompletion("batch_1", 10_000L);
		assertEquals("ended", resp.status());
		assertEquals(1, resp.requestCounts().completed());
		assertEquals(2, this.getHits.get());
		client.close();
	}
}
