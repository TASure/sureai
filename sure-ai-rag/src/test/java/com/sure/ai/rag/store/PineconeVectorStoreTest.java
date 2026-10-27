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

/**
 * {@link PineconeVectorStore} 单元测试：本地 {@link HttpServer} mock，零真实网络。
 *
 * @author sureai
 * @since 1.8.0
 */
public class PineconeVectorStoreTest {

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

	private PineconeVectorStore.Builder baseBuilder() {
		return PineconeVectorStore.builder().baseUrl(baseUrl);
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
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "text-" + id,
				Map.of("genre", "doc"));
	}

	/** addAll：POST /vectors/upsert，body 含 id/values/metadata，Api-Key 头正确。 */
	@Test
	public void testUpsert() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		AtomicReference<String> authRef = new AtomicReference<>();
		this.server.createContext("/vectors/upsert", ex -> {
			authRef.set(ex.getRequestHeaders().getFirst("Api-Key"));
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"upsertedCount\":2}");
		});
		PineconeVectorStore store = baseBuilder().apiKey("pc-secret").build();
		store.addAll(List.of(sampleVector("v1"), sampleVector("v2")));

		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		JsonArray vectors = body.getJsonArray("vectors");
		assertEquals(2, vectors.size());
		JsonObject v0 = vectors.getJsonObject(0);
		assertEquals("v1", v0.getString("id"));
		assertEquals(2, v0.getJsonArray("values").size());
		JsonObject metadata = v0.getJsonObject("metadata");
		assertEquals("text-v1", metadata.getString("text"));
		assertEquals("doc", metadata.getString("genre"));
		assertEquals("pc-secret", authRef.get());
	}

	/** query：返回 matches，断言解析 id/text/score 降序。 */
	@Test
	public void testQuery() {
		this.server.createContext("/query", ex -> respond(ex, 200,
				"{\"matches\":["
						+ "{\"id\":\"v1\",\"score\":0.2,\"values\":[0.1,0.2],"
						+ "\"metadata\":{\"text\":\"low\",\"genre\":\"a\"}},"
						+ "{\"id\":\"v2\",\"score\":0.95,\"values\":[0.3,0.4],"
						+ "\"metadata\":{\"text\":\"high\"}}"
						+ "]}"));
		PineconeVectorStore store = baseBuilder().build();
		List<SimilaritySearchResult> results = store
				.similaritySearch(new float[] { 0.1f, 0.2f }, 2);
		assertEquals(2, results.size());
		assertEquals("v2", results.get(0).id());
		assertEquals("high", results.get(0).text());
		assertEquals(0.95, results.get(0).score(), 1e-9);
		assertEquals("v1", results.get(1).id());
		assertEquals(0.2, results.get(1).score(), 1e-9);
		assertEquals("a", results.get(1).metadata().get("genre"));
	}

	/** delete：POST /vectors/delete，body 含 ids。 */
	@Test
	public void testDelete() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/vectors/delete", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{}");
		});
		PineconeVectorStore store = baseBuilder().build();
		assertTrue(store.delete("v1"));
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals("v1", body.getJsonArray("ids").getString(0));
	}

	/** clear：deleteAll=true。 */
	@Test
	public void testClear() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/vectors/delete", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{}");
		});
		PineconeVectorStore store = baseBuilder().build();
		store.clear();
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertTrue(body.getBoolean("deleteAll"));
	}

	/** 非 2xx 响应抛 AiException。 */
	@Test
	public void testErrorStatusThrows() {
		this.server.createContext("/query", ex -> respond(ex, 401, "{\"error\":\"unauth\"}"));
		PineconeVectorStore store = baseBuilder().build();
		assertThrows(AiException.class,
				() -> store.similaritySearch(new float[] { 0.1f, 0.2f }, 1));
	}

	/** 带 filter 检索：请求体含 Pinecone $eq filter。 */
	@Test
	public void testQueryWithFilter() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/query", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"matches\":[]}");
		});
		PineconeVectorStore store = baseBuilder().build();
		store.similaritySearch(new float[] { 0.1f, 0.2f }, 2,
				FilterExpression.eq("genre", "doc"));
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		JsonObject filter = body.getJsonObject("filter");
		assertEquals("doc", filter.getJsonObject("genre").getString("$eq"));
	}

	/** size：data-plane 无计数端点，返回 -1。 */
	@Test
	public void testSizeUnsupported() {
		assertEquals(-1, baseBuilder().build().size());
	}
}
