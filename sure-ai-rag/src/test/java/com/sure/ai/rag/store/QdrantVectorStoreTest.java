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
 * {@link QdrantVectorStore} 单元测试：本地 {@link HttpServer} mock，零真实网络。
 *
 * @author sureai
 * @since 1.8.0
 */
public class QdrantVectorStoreTest {

	private static final String COLLECTION = "test-collection";

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

	private QdrantVectorStore.Builder baseBuilder() {
		return QdrantVectorStore.builder()
				.baseUrl(baseUrl)
				.collectionName(COLLECTION);
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

	/** addAll：PUT points，body 含 id/vector/payload，鉴权头正确。 */
	@Test
	public void testAddAll() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		AtomicReference<String> authRef = new AtomicReference<>();
		this.server.createContext("/collections/" + COLLECTION + "/points", ex -> {
			authRef.set(ex.getRequestHeaders().getFirst("api-key"));
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"result\":{\"status\":\"ok\"},\"status\":\"ok\"}");
		});
		QdrantVectorStore store = baseBuilder().apiKey("secret").build();
		store.addAll(List.of(sampleVector("v1"), sampleVector("v2")));

		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		com.sure.ai.internal.json.JsonArray points = body.getJsonArray("points");
		assertEquals(2, points.size());
		JsonObject p0 = points.getJsonObject(0);
		assertEquals("v1", p0.getString("id"));
		assertEquals(3, p0.getJsonArray("vector").size());
		JsonObject payload = p0.getJsonObject("payload");
		assertEquals("text-v1", payload.getString("text"));
		assertEquals("v1.pdf", payload.getString("source"));
		assertEquals("secret", authRef.get());
	}

	/** similaritySearch：返回 points，断言解析结果 id/text/score 降序。 */
	@Test
	public void testSearch() {
		this.server.createContext("/collections/" + COLLECTION + "/points/search", ex -> respond(ex,
				200, "{\"result\":["
						+ "{\"id\":\"v1\",\"score\":0.5,\"payload\":{\"text\":\"low\",\"source\":\"a\"},"
						+ "\"vector\":[0.1,0.2]},"
						+ "{\"id\":\"v2\",\"score\":0.9,\"payload\":{\"text\":\"high\"},"
						+ "\"vector\":[0.3,0.4]}"
						+ "],\"status\":\"ok\"}"));
		QdrantVectorStore store = baseBuilder().build();
		List<SimilaritySearchResult> results = store
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 2);
		assertEquals(2, results.size());
		assertEquals("v2", results.get(0).id());
		assertEquals("high", results.get(0).text());
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("v1", results.get(1).id());
		assertEquals(0.5, results.get(1).score(), 1e-9);
		assertNotNull(results.get(0).embedding());
	}

	/** delete：POST points/delete，body 含 id，返回 true。 */
	@Test
	public void testDelete() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/collections/" + COLLECTION + "/points/delete", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"result\":{\"status\":\"ok\"}}");
		});
		QdrantVectorStore store = baseBuilder().build();
		assertTrue(store.delete("v1"));
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals("v1", body.getJsonArray("points").getString(0));
	}

	/** clear：DELETE collection 被调用。 */
	@Test
	public void testClear() {
		AtomicReference<String> methodRef = new AtomicReference<>();
		this.server.createContext("/collections/" + COLLECTION, ex -> {
			methodRef.set(ex.getRequestMethod());
			respond(ex, 200, "{\"result\":true}");
		});
		QdrantVectorStore store = baseBuilder().build();
		store.clear();
		assertEquals("DELETE", methodRef.get());
	}

	/** 非 2xx 响应抛 AiException。 */
	@Test
	public void testErrorStatusThrows() {
		this.server.createContext("/collections/" + COLLECTION + "/points/search",
				ex -> respond(ex, 500, "{\"error\":\"boom\"}"));
		QdrantVectorStore store = baseBuilder().build();
		assertThrows(AiException.class,
				() -> store.similaritySearch(new float[] { 0.1f, 0.2f }, 1));
	}

	/** 带 filter 检索：请求体含翻译后的 Qdrant filter。 */
	@Test
	public void testSearchWithFilter() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/collections/" + COLLECTION + "/points/search", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"result\":[]}");
		});
		QdrantVectorStore store = baseBuilder().build();
		FilterExpression filter = FilterExpression.eq("tenant", "acme");
		store.similaritySearch(new float[] { 0.1f, 0.2f }, 2, filter);
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		JsonObject f = body.getJsonObject("filter");
		assertEquals("tenant", f.getJsonArray("must").getJsonObject(0).getString("key"));
		assertEquals("acme", f.getJsonArray("must").getJsonObject(0)
				.getJsonObject("match").getString("value"));
	}

	/** size：count 端点返回计数。 */
	@Test
	public void testSize() {
		this.server.createContext("/collections/" + COLLECTION + "/points/count",
				ex -> respond(ex, 200, "{\"result\":{\"count\":7}}"));
		QdrantVectorStore store = baseBuilder().dimension(4).build();
		assertEquals(7, store.size());
	}
}
