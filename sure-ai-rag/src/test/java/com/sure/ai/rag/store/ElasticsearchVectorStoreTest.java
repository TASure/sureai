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

package com.sure.ai.rag.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.filter.FilterExpression;

/**
 * {@link ElasticsearchVectorStore} 单元测试：本地 {@link HttpServer} mock，零真实网络。
 *
 * @author sureai
 * @since 1.8.0
 */
public class ElasticsearchVectorStoreTest {

	private static final String INDEX = "test-index";

	private HttpServer server;
	private String baseUrl;

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port;
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	private ElasticsearchVectorStore.Builder baseBuilder() {
		return ElasticsearchVectorStore.builder().baseUrl(baseUrl).indexName(INDEX);
	}

	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			if (bytes.length > 0) {
				os.write(bytes);
			}
		}
	}

	private static String readBody(HttpExchange ex) throws IOException {
		return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
	}

	private static Vector sampleVector(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f, 0.3f }, "text-" + id,
				Map.of("source", id + ".pdf"));
	}

	/** addAll：_bulk NDJSON 含 action/source 行、向量/文本/metadata，鉴权头正确。 */
	@Test
	public void testAddAll() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		AtomicReference<String> authRef = new AtomicReference<>();
		this.server.createContext("/" + INDEX + "/_bulk", ex -> {
			authRef.set(ex.getRequestHeaders().getFirst("Authorization"));
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"errors\":false,\"items\":[]}");
		});
		baseBuilder().apiKey("secret").build().addAll(List.of(sampleVector("v1"), sampleVector("v2")));
		String[] lines = bodyRef.get().split("\n");
		assertEquals(4, lines.length);
		JsonObject action = (JsonObject) Json.parse(lines[0]);
		assertEquals("v1", action.getJsonObject("index").getString("_id"));
		JsonObject source = (JsonObject) Json.parse(lines[1]);
		assertEquals(3, source.getJsonArray("vector").size());
		assertEquals("text-v1", source.getString("text"));
		assertEquals("v1.pdf", source.getString("source"));
		assertEquals("ApiKey secret", authRef.get());
	}

	/** similaritySearch：_search knn 体正确，解析 hits 为结果，score 还原为余弦降序。 */
	@Test
	public void testSearch() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/" + INDEX + "/_search", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"hits\":{\"hits\":["
					+ "{\"_id\":\"v1\",\"_score\":0.6,\"_source\":{\"text\":\"low\",\"vector\":[0.1,0.2,0.3]}},"
					+ "{\"_id\":\"v2\",\"_score\":0.95,\"_source\":{\"text\":\"high\",\"vector\":[0.4,0.5,0.6]}}"
					+ "]}}");
		});
		ElasticsearchVectorStore store = baseBuilder().build();
		List<SimilaritySearchResult> results = store.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 2);
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		JsonObject knn = body.getJsonObject("knn");
		assertEquals("vector", knn.getString("field"));
		assertEquals(2, knn.getInt("k"));
		assertTrue(knn.has("query_vector"));
		assertTrue(knn.has("num_candidates"));
		assertEquals(2, results.size());
		assertEquals("v2", results.get(0).id());
		assertEquals("high", results.get(0).text());
		// ES cosine _score=0.95 -> cosine=0.9
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("v1", results.get(1).id());
		assertEquals(0.2, results.get(1).score(), 1e-9);
		assertNotNull(results.get(0).embedding());
	}

	/** delete：_delete_by_query body 含 ids。 */
	@Test
	public void testDelete() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/" + INDEX + "/_delete_by_query", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"deleted\":1}");
		});
		assertTrue(baseBuilder().build().delete("v1"));
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals("v1", body.getJsonObject("query").getJsonObject("ids").getJsonArray("values").getString(0));
	}

	/** clear：DELETE index 被调用。 */
	@Test
	public void testClear() {
		AtomicReference<String> methodRef = new AtomicReference<>();
		this.server.createContext("/" + INDEX, ex -> {
			methodRef.set(ex.getRequestMethod());
			respond(ex, 200, "{\"acknowledged\":true}");
		});
		baseBuilder().build().clear();
		assertEquals("DELETE", methodRef.get());
	}

	/** size：_count 返回 count。 */
	@Test
	public void testSize() {
		this.server.createContext("/" + INDEX + "/_count", ex -> respond(ex, 200, "{\"count\":7}"));
		assertEquals(7, baseBuilder().build().size());
	}

	/** 非 2xx 响应抛 AiException。 */
	@Test
	public void testErrorStatusThrows() {
		this.server.createContext("/" + INDEX + "/_search", ex -> respond(ex, 500, "{\"error\":\"boom\"}"));
		assertThrows(AiException.class,
				() -> baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 1));
	}

	/** 带 filter：knn.filter 含 bool.must.term。 */
	@Test
	public void testSearchWithFilter() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/" + INDEX + "/_search", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"hits\":{\"hits\":[]}}");
		});
		baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 2,
				FilterExpression.eq("tenant", "acme"));
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		JsonObject filter = body.getJsonObject("knn").getJsonObject("filter");
		// 单 Eq 翻译为裸 term 查询对象。
		assertEquals("acme", filter.getJsonObject("term").getString("tenant"));
	}

	/** createIndex：PUT mapping dense_vector/cosine。 */
	@Test
	public void testCreateIndex() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/" + INDEX, ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"acknowledged\":true}");
		});
		baseBuilder().dimension(4).build().createIndex();
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		JsonObject vec = body.getJsonObject("mappings").getJsonObject("properties").getJsonObject("vector");
		assertEquals("dense_vector", vec.getString("type"));
		assertEquals("cosine", vec.getString("similarity"));
		assertEquals(4, vec.getInt("dims"));
	}
}
