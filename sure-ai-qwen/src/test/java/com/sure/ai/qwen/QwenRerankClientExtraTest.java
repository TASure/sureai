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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
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
import com.sure.ai.model.RerankRequest;
import com.sure.ai.model.RerankResponse;

/**
 * {@link QwenRerankClient} 边界分支测试：extra 透传、无 results 响应。
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenRerankClientExtraTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private boolean noResults;

	/** 启动 mock。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/compatible-mode/v1";
		this.noResults = false;
		this.server.createContext("/", this::route);
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 路由 /reranks。 */
	private void route(HttpExchange ex) throws IOException {
		byte[] in = ex.getRequestBody().readAllBytes();
		this.lastBody.set(new String(in, StandardCharsets.UTF_8));
		String body = this.noResults ? "{}" : "{\"results\":[{\"index\":0,\"relevance_score\":0.9,\"document\":{\"text\":\"doc-a\"}}]}";
		byte[] out = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	/** 构造客户端。 */
	private QwenRerankClient newClient() {
		return new QwenRerankClient(AiConfig.builder().apiKey("sk").baseUrl(this.baseUrl).build());
	}

	/** extra 字段透传到请求体。 */
	@Test
	public void testExtraPassthrough() {
		QwenRerankClient client = newClient();
		RerankResponse resp = client.rerank(RerankRequest.builder()
			.model("qwen3-rerank").query("q").documents(List.of("doc-a"))
			.topN(3).extra("override", true).build());
		assertTrue(this.lastBody.get(), this.lastBody.get().contains("\"override\":true"));
		assertTrue(this.lastBody.get(), this.lastBody.get().contains("\"top_n\":3"));
		assertEquals(1, resp.results().size());
		assertEquals(0.9, resp.results().get(0).relevanceScore(), 1e-6);
		client.close();
	}

	/** 响应无 results → 空结果列表。 */
	@Test
	public void testNoResults() {
		this.noResults = true;
		QwenRerankClient client = newClient();
		RerankResponse resp = client.rerank(RerankRequest.builder()
			.model("qwen3-rerank").query("q").documents(List.of("a")).build());
		assertEquals(0, resp.results().size());
		client.close();
	}
}
