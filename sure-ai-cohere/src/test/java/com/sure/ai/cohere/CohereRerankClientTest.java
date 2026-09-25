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

package com.sure.ai.cohere;

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
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.model.RerankRequest;
import com.sure.ai.model.RerankResponse;
import com.sure.ai.model.RerankResult;

/**
 * {@link CohereRerankClient} 与 {@link CohereUtil#rerank} 测试：本地 HttpServer mock /v2/rerank。
 *
 * @author sureai
 * @since 1.3.0
 */
public class CohereRerankClientTest {

	private static final String API_KEY = "test-key";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> reqBody = new AtomicReference<>();
	private final AtomicReference<String> reqAuth = new AtomicReference<>();
	private final AtomicReference<String> reqMethod = new AtomicReference<>();

	/** 失败模式：null=成功，"auth"=返回 401。 */
	private String mode;

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v2";
		this.mode = null;
		registerHandlers();
		CohereUtil.resetRerankClient();
	}

	/** 停止服务并清理单例。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		CohereUtil.resetRerankClient();
	}

	/** 注册 /v2/rerank 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/v2/rerank")) {
				this.reqMethod.set(exchange.getRequestMethod());
				this.reqAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.reqBody.set(new String(in, StandardCharsets.UTF_8));
				if ("auth".equals(this.mode)) {
					respond(exchange, 401, "{\"error\":{\"message\":\"invalid token\"}}");
					return;
				}
				String body = "{\"model\":\"rerank-v3.5\",\"results\":["
					+ "{\"index\":2,\"relevance_score\":0.95,\"document\":{\"text\":\"doc-C\"}},"
					+ "{\"index\":0,\"relevance_score\":0.42,\"document\":{\"text\":\"doc-A\"}},"
					+ "{\"index\":1,\"relevance_score\":0.10,\"document\":{\"text\":\"doc-B\"}}"
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
	private CohereRerankClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).maxRetries(0).build();
		return new CohereRerankClient(cfg);
	}

	/** 成功：断言 POST /rerank、请求体 model/query/documents/top_n、鉴权头与响应解析 index/score/document。 */
	@Test
	public void testRerankSuccess() {
		CohereRerankClient client = newClient();
		RerankResponse resp = client.rerank(RerankRequest.builder()
			.model(CohereModels.RERANK_V3_5)
			.query("什么是重排")
			.documents(List.of("doc-A", "doc-B", "doc-C"))
			.topN(3)
			.build());
		assertEquals("POST", this.reqMethod.get());
		assertEquals("rerank-v3.5", resp.model());
		List<RerankResult> results = resp.results();
		assertEquals(3, results.size());
		assertEquals(2, results.get(0).index());
		assertEquals(0.95, results.get(0).relevanceScore(), 1e-9);
		assertEquals("doc-C", results.get(0).document());
		assertEquals(0, results.get(1).index());
		assertEquals(0.42, results.get(1).relevanceScore(), 1e-9);
		assertEquals("doc-A", results.get(1).document());
		assertEquals(1, results.get(2).index());
		assertEquals(0.10, results.get(2).relevanceScore(), 1e-9);
		assertEquals("doc-B", results.get(2).document());
		assertNotNull(results.get(0).rawJson());
		// 认证头
		assertEquals("Bearer " + API_KEY, this.reqAuth.get());
		// 请求体
		String body = this.reqBody.get();
		assertTrue(body.contains("\"model\":\"rerank-v3.5\""));
		assertTrue(body.contains("\"query\":\"什么是重排\""));
		assertTrue(body.contains("\"documents\":[\"doc-A\",\"doc-B\",\"doc-C\"]"));
		assertTrue(body.contains("\"top_n\":3"));
		client.close();
	}

	/** 成功但未设置 topN：请求体不应携带 top_n。 */
	@Test
	public void testRerankNoTopN() {
		CohereRerankClient client = newClient();
		client.rerank(RerankRequest.builder()
			.model(CohereModels.RERANK_V3_5)
			.query("q")
			.documents(List.of("a", "b"))
			.build());
		String body = this.reqBody.get();
		assertTrue(body.contains("\"documents\":[\"a\",\"b\"]"));
		assertTrue("top_n should be omitted", !body.contains("top_n"));
		client.close();
	}

	/** 认证失败：401 映射为 AiAuthException。 */
	@Test
	public void testRerankUnauthorized() {
		this.mode = "auth";
		CohereRerankClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.rerank(RerankRequest.builder()
			.model(CohereModels.RERANK_V3_5).query("q").documents(List.of("a")).build()));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("cohere-rerank", newClient().name());
	}

	/** Util 便捷方法：反射注入指向 mock 的单例后调用 rerank(query, documents)。 */
	@SuppressWarnings("unchecked")
	@Test
	public void testUtilRerankConvenience() throws Exception {
		Field f = CohereUtil.class.getDeclaredField("RERANK");
		f.setAccessible(true);
		((SingletonHolder<CohereRerankClient>) f.get(null)).set(newClient());
		RerankResponse resp = CohereUtil.rerank("什么是重排", List.of("doc-A", "doc-B", "doc-C"));
		assertEquals(3, resp.results().size());
		assertEquals("doc-C", resp.results().get(0).document());
		String body = this.reqBody.get();
		assertTrue(body.contains("\"model\":\"rerank-v3.5\""));
	}
}
