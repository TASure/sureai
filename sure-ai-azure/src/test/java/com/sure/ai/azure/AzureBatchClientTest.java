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

package com.sure.ai.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;

/**
 * {@link AzureBatchClient} 测试：本地 HttpServer mock Azure 风格 batches 路径与 api-key 鉴权。
 *
 * @author sureai
 * @since 0.2.0
 */
public class AzureBatchClientTest {

	private static final String API_KEY = "azure-key";
	private static final String BATCH_ID = "batch_azure_1";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> reqBody = new AtomicReference<>();
	private final AtomicReference<String> reqApiKey = new AtomicReference<>();
	private final AtomicReference<String> reqAuth = new AtomicReference<>();
	private final AtomicReference<String> rawQuery = new AtomicReference<>();

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		registerHandlers();
	}

	/** 停止服务并清理单例。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		AzureUtil.resetBatchClient();
	}

	/** 注册 Azure batches 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String method = exchange.getRequestMethod();
			this.rawQuery.set(exchange.getRequestURI().getQuery());
			if (method.equals("POST") && path.equals("/openai/v1/batches")) {
				this.reqApiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
				this.reqAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.reqBody.set(new String(in, StandardCharsets.UTF_8));
				String body = "{\"id\":\"" + BATCH_ID + "\",\"object\":\"batch\",\"status\":\"validating\","
					+ "\"created_at\":1711471649,\"completed_at\":null,"
					+ "\"request_counts\":{\"total\":50,\"completed\":0,\"failed\":0}}";
				respond(exchange, 200, body);
				return;
			}
			if (method.equals("GET") && path.equals("/openai/v1/batches/" + BATCH_ID)) {
				String body = "{\"id\":\"" + BATCH_ID + "\",\"object\":\"batch\",\"status\":\"completed\","
					+ "\"created_at\":1711471649,\"completed_at\":1711479999,"
					+ "\"request_counts\":{\"total\":50,\"completed\":50,\"failed\":0}}";
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

	/** 构造指向 mock 的 Azure 客户端。 */
	private AzureBatchClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader("api-version", "2024-10-21").build();
		return new AzureBatchClient(cfg);
	}

	/** 提交：断言 Azure 路径、api-version 查询参数、api-key 头。 */
	@Test
	public void testBatchCreate() {
		AzureBatchClient client = newClient();
		BatchResponse resp = client.createBatch(BatchRequest.builder()
			.model("gpt-4o").inputFileId("file-azure-1").completionWindow("24h").build());
		assertEquals(BATCH_ID, resp.id());
		assertEquals("validating", resp.status());
		assertEquals(API_KEY, this.reqApiKey.get());
		assertEquals("Bearer 头不应出现", null, this.reqAuth.get());
		assertEquals("api-version=2024-10-21", this.rawQuery.get());
		String body = this.reqBody.get();
		assertTrue(body.contains("\"input_file_id\":\"file-azure-1\""));
		assertTrue(body.contains("\"endpoint\":\"/v1/chat/completions\""));
		assertTrue(body.contains("\"completion_window\":\"24h\""));
		client.close();
	}

	/** 查询：断言 Azure 路径带 api-version、status/request_counts 解析。 */
	@Test
	public void testBatchGet() {
		AzureBatchClient client = newClient();
		BatchResponse resp = client.getBatch(BATCH_ID);
		assertEquals(BATCH_ID, resp.id());
		assertTrue(resp.isCompleted());
		assertEquals(50, resp.requestCounts().total());
		assertEquals(50, resp.requestCounts().completed());
		assertEquals("api-version=2024-10-21", this.rawQuery.get());
		client.close();
	}

	/** name() 与 apiVersion()。 */
	@Test
	public void testNameAndApiVersion() {
		AzureBatchClient client = newClient();
		assertEquals("azure-batch", client.name());
		assertEquals("2024-10-21", client.apiVersion());
		assertNotNull(AzureClient.DEFAULT_API_VERSION);
		client.close();
	}
}
