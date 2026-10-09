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
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.typesense.TypesenseVectorStore;

/**
 * {@link TypesenseVectorStore} 单元测试：本地 {@link HttpServer} mock，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class TypesenseVectorStoreTest {

	private static final String COLLECTION = "test-coll";

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

	private TypesenseVectorStore.Builder baseBuilder() {
		return TypesenseVectorStore.builder().baseUrl(baseUrl).collectionName(COLLECTION);
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

	/** addAll：POST documents?action=upsert，body 为数组，含 id/text/vector/metadata，鉴权头正确。 */
	@Test
	public void testAddAll() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		AtomicReference<String> authRef = new AtomicReference<>();
		AtomicReference<String> pathRef = new AtomicReference<>();
		this.server.createContext("/collections/" + COLLECTION + "/documents", ex -> {
			authRef.set(ex.getRequestHeaders().getFirst("X-TYPESENSE-API-KEY"));
			pathRef.set(ex.getRequestURI().toString());
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"num_imported\":2}");
		});
		baseBuilder().apiKey("secret").build().addAll(List.of(sampleVector("v1"), sampleVector("v2")));

		assertTrue(pathRef.get(), pathRef.get().contains("action=upsert"));
		JsonArray arr = (JsonArray) Json.parse(bodyRef.get());
		assertEquals(2, arr.size());
		JsonObject d0 = arr.getJsonObject(0);
		assertEquals("v1", d0.getString("id"));
		assertEquals(3, d0.getJsonArray("vector").size());
		assertEquals("text-v1", d0.getString("text"));
		assertEquals("v1.pdf", d0.getString("source"));
		assertEquals("secret", authRef.get());
	}

	/** similaritySearch：GET search，断言 q=* 与 vector_query 参数，解析 hits/vector_distance 还原得分。 */
	@Test
	public void testSearch() {
		AtomicReference<String> queryRef = new AtomicReference<>();
		this.server.createContext("/collections/" + COLLECTION + "/documents/search", ex -> {
			queryRef.set(ex.getRequestURI().getQuery());
			respond(ex, 200, "{\"hits\":["
					+ "{\"document\":{\"id\":\"v1\",\"text\":\"low\",\"source\":\"a\"},\"vector_distance\":0.2},"
					+ "{\"document\":{\"id\":\"v2\",\"text\":\"high\"},\"vector_distance\":0.1}]"
					+ ",\"found\":2}");
		});
		List<SimilaritySearchResult> results = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 2);
		String q = queryRef.get();
		assertTrue(q.contains("q=*"));
		assertTrue(q.contains("vector_query="));
		// v2 distance 0.1 -> score 0.9；v1 distance 0.2 -> score 0.8；降序 v2 在前
		assertEquals(2, results.size());
		assertEquals("v2", results.get(0).id());
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("high", results.get(0).text());
		assertEquals("v1", results.get(1).id());
		assertEquals(0.8, results.get(1).score(), 1e-9);
		assertEquals("a", results.get(1).metadata().get("source"));
	}

	/** 带 filter：search 查询串含 filter_by。 */
	@Test
	public void testSearchWithFilter() {
		AtomicReference<String> queryRef = new AtomicReference<>();
		this.server.createContext("/collections/" + COLLECTION + "/documents/search", ex -> {
			queryRef.set(ex.getRequestURI().getQuery());
			respond(ex, 200, "{\"hits\":[],\"found\":0}");
		});
		baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 2,
				FilterExpression.eq("tenant", "acme"));
		assertTrue(queryRef.get().contains("filter_by="));
		assertTrue(java.net.URLDecoder.decode(queryRef.get(), StandardCharsets.UTF_8)
				.contains("tenant:=\"acme\""));
	}

	/** delete：DELETE documents/{id}，num_deleted=1 返回 true。 */
	@Test
	public void testDelete() {
		AtomicReference<String> pathRef = new AtomicReference<>();
		this.server.createContext("/collections/" + COLLECTION + "/documents/v1", ex -> {
			pathRef.set(ex.getRequestMethod() + " " + ex.getRequestURI());
			respond(ex, 200, "{\"name\":\"v1\",\"num_deleted\":1}");
		});
		assertTrue(baseBuilder().build().delete("v1"));
		assertTrue(pathRef.get().startsWith("DELETE"));
	}

	/** size：GET collection 返回 num_doc。 */
	@Test
	public void testSize() {
		this.server.createContext("/collections/" + COLLECTION,
				ex -> respond(ex, 200, "{\"name\":\"x\",\"num_doc\":7}"));
		assertEquals(7, baseBuilder().build().size());
	}

	/** createCollection：POST /collections body 含 fields（float[] + num_dim）。 */
	@Test
	public void testCreateCollection() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/collections", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"name\":\"x\",\"num_doc\":0}");
		});
		baseBuilder().dimension(3).autoCreateCollection(true).build();
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals(COLLECTION, body.getString("name"));
		JsonArray fields = body.getJsonArray("fields");
		boolean foundVec = false;
		for (int i = 0; i < fields.size(); i++) {
			JsonObject f = fields.getJsonObject(i);
			if ("vector".equals(f.getString("name"))) {
				foundVec = true;
				assertEquals("float[]", f.getString("type"));
				assertEquals(3, f.getInt("num_dim"));
			}
		}
		assertTrue("vector field 未声明", foundVec);
	}

	/** 非 2xx 响应抛 AiException。 */
	@Test
	public void testErrorStatusThrows() {
		this.server.createContext("/collections/" + COLLECTION + "/documents/search",
				ex -> respond(ex, 401, "Unauthorized"));
		assertThrows(AiException.class,
				() -> baseBuilder().build().similaritySearch(new float[] { 0.1f }, 1));
	}

	/** 空结果解析。 */
	@Test
	public void testSearchEmpty() {
		this.server.createContext("/collections/" + COLLECTION + "/documents/search",
				ex -> respond(ex, 200, "{\"hits\":[],\"found\":0}"));
		assertTrue(baseBuilder().build().similaritySearch(new float[] { 0.1f }, 1).isEmpty());
	}
}
