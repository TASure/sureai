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
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.model.RerankRequest;
import com.sure.ai.model.RerankResponse;
import com.sure.ai.model.RerankResult;

/**
 * {@link QwenRerankClient} 与 {@link QwenUtil#rerank} 测试：本地 HttpServer mock /reranks。
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenRerankClientTest {

	private static final String API_KEY = "test-key";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> reqBody = new AtomicReference<>();
	private final AtomicReference<String> reqAuth = new AtomicReference<>();

	/** 失败模式：null=成功，"fail"=返回 500。 */
	private String mode;

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort()
			+ "/compatible-mode/v1";
		this.mode = null;
		registerHandlers();
		QwenUtil.resetRerankClient();
	}

	/** 停止服务并清理单例。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		QwenUtil.resetRerankClient();
	}

	/** 注册 /reranks 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/compatible-mode/v1/reranks")) {
				this.reqAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.reqBody.set(new String(in, StandardCharsets.UTF_8));
				if ("fail".equals(this.mode)) {
					respond(exchange, 500, "{\"error\":{\"message\":\"boom\"}}");
					return;
				}
				String body = "{\"model\":\"qwen3-rerank\",\"results\":["
					+ "{\"index\":1,\"relevance_score\":0.95,\"document\":{\"text\":\"doc-B\"}},"
					+ "{\"index\":0,\"relevance_score\":0.42,\"document\":{\"text\":\"doc-A\"}}"
					+ "]}";
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

	/** 构造指向 mock 的客户端（关闭重试以加速失败用例）。 */
	private QwenRerankClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).maxRetries(0).build();
		return new QwenRerankClient(cfg);
	}

	/** 成功：断言请求体 model/query/documents/top_n 与响应解析 index/score/document。 */
	@Test
	public void testRerankSuccess() {
		QwenRerankClient client = newClient();
		RerankResponse resp = client.rerank(RerankRequest.builder()
			.model(QwenModels.QWEN3_RERANK)
			.query("什么是重排")
			.documents(List.of("doc-A", "doc-B"))
			.topN(2)
			.build());
		assertEquals("qwen3-rerank", resp.model());
		List<RerankResult> results = resp.results();
		assertEquals(2, results.size());
		assertEquals(1, results.get(0).index());
		assertEquals(0.95, results.get(0).relevanceScore(), 1e-9);
		assertEquals("doc-B", results.get(0).document());
		assertEquals(0, results.get(1).index());
		assertEquals(0.42, results.get(1).relevanceScore(), 1e-9);
		assertEquals("doc-A", results.get(1).document());
		assertNotNull(results.get(0).rawJson());
		assertEquals("Bearer " + API_KEY, this.reqAuth.get());
		String body = this.reqBody.get();
		assertTrue(body.contains("\"model\":\"qwen3-rerank\""));
		assertTrue(body.contains("\"query\":\"什么是重排\""));
		assertTrue(body.contains("\"documents\":[\"doc-A\",\"doc-B\"]"));
		assertTrue(body.contains("\"top_n\":2"));
		client.close();
	}

	/** 成功但未设置 topN：请求体不应携带 top_n。 */
	@Test
	public void testRerankNoTopN() {
		QwenRerankClient client = newClient();
		client.rerank(RerankRequest.builder()
			.model(QwenModels.QWEN3_RERANK)
			.query("q")
			.documents(List.of("a", "b"))
			.build());
		String body = this.reqBody.get();
		assertTrue(body.contains("\"documents\":[\"a\",\"b\"]"));
		assertTrue("top_n should be omitted", !body.contains("top_n"));
		client.close();
	}

	/** 失败：500 映射为 AiApiException。 */
	@Test
	public void testRerankFailed() {
		this.mode = "fail";
		QwenRerankClient client = newClient();
		assertThrows(AiApiException.class, () -> client.rerank(RerankRequest.builder()
			.model(QwenModels.QWEN3_RERANK).query("q").documents(List.of("a")).build()));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("qwen-rerank", newClient().name());
	}

	/** Util 便捷方法：反射注入指向 mock 的单例后调用 rerank(query, documents)。 */
	@SuppressWarnings("unchecked")
	@Test
	public void testUtilRerankConvenience() throws Exception {
		Field f = QwenUtil.class.getDeclaredField("RERANK");
		f.setAccessible(true);
		((SingletonHolder<QwenRerankClient>) f.get(null)).set(newClient());
		RerankResponse resp = QwenUtil.rerank("什么是重排", List.of("doc-A", "doc-B"));
		assertEquals(2, resp.results().size());
		assertEquals("doc-B", resp.results().get(0).document());
		String body = this.reqBody.get();
		assertTrue(body.contains("\"model\":\"qwen3-rerank\""));
	}
}
