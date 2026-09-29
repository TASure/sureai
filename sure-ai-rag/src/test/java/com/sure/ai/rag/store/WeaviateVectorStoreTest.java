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
 * {@link WeaviateVectorStore} 单元测试：本地 {@link HttpServer} mock，零真实网络。
 *
 * @author sureai
 * @since 1.8.0
 */
public class WeaviateVectorStoreTest {

	private static final String CLASS = "Article";

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

	private WeaviateVectorStore.Builder baseBuilder() {
		return WeaviateVectorStore.builder().baseUrl(baseUrl).className(CLASS);
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
				Map.of("source", id + ".pdf"));
	}

	/** add：POST /v1/objects，body 含 class/properties/id/vector。 */
	@Test
	public void testAdd() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/v1/objects", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"id\":\"uuid-returned\"}");
		});
		WeaviateVectorStore store = baseBuilder().build();
		store.add(sampleVector("v1"));

		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals(CLASS, body.getString("class"));
		assertNotNull(body.getString("id"));
		JsonObject props = body.getJsonObject("properties");
		assertEquals("text-v1", props.getString("text"));
		assertEquals("v1", props.getString("_doc_id"));
		assertEquals("v1.pdf", props.getString("source"));
		assertEquals(2, body.getJsonArray("vector").size());
	}

	/** addAll：POST /v1/batch/objects，objects 数组。 */
	@Test
	public void testAddAll() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/v1/batch/objects", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{}");
		});
		WeaviateVectorStore store = baseBuilder().build();
		store.addAll(List.of(sampleVector("v1"), sampleVector("v2")));
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals(2, body.getJsonArray("objects").size());
	}

	/** similaritySearch：GraphQL nearVector，解析 data.Get 结果并按 score 降序。 */
	@Test
	public void testSearch() {
		this.server.createContext("/v1/graphql", ex -> respond(ex, 200,
				"{\"data\":{\"Get\":{\"" + CLASS + "\":["
						+ "{\"text\":\"low\",\"_doc_id\":\"v1\","
						+ "\"_additional\":{\"id\":\"u1\",\"distance\":0.5,\"vector\":[0.1,0.2]}},"
						+ "{\"text\":\"high\",\"_doc_id\":\"v2\","
						+ "\"_additional\":{\"id\":\"u2\",\"distance\":0.1,\"vector\":[0.3,0.4]}}"
						+ "]}}}"));
		WeaviateVectorStore store = baseBuilder().build();
		List<SimilaritySearchResult> results = store
				.similaritySearch(new float[] { 0.1f, 0.2f }, 2);
		assertEquals(2, results.size());
		assertEquals("v2", results.get(0).id());
		assertEquals("high", results.get(0).text());
		assertEquals(1d / 1.1d, results.get(0).score(), 1e-9);
		assertEquals("v1", results.get(1).id());
		assertEquals(1d / 1.5d, results.get(1).score(), 1e-9);
	}

	/** delete：DELETE /v1/objects/{uuid} 路径非空。 */
	@Test
	public void testDelete() {
		AtomicReference<String> pathRef = new AtomicReference<>();
		this.server.createContext("/v1/objects/", ex -> {
			pathRef.set(ex.getRequestURI().getPath());
			respond(ex, 204, "");
		});
		WeaviateVectorStore store = baseBuilder().build();
		assertTrue(store.delete("v1"));
		assertTrue(pathRef.get().startsWith("/v1/objects/"));
		assertTrue(pathRef.get().length() > "/v1/objects/".length());
	}

	/** clear：DELETE /v1/schema/{class}。 */
	@Test
	public void testClear() {
		AtomicReference<String> methodRef = new AtomicReference<>();
		this.server.createContext("/v1/schema/" + CLASS, ex -> {
			methodRef.set(ex.getRequestMethod());
			respond(ex, 200, "");
		});
		WeaviateVectorStore store = baseBuilder().build();
		store.clear();
		assertEquals("DELETE", methodRef.get());
	}

	/** 非 2xx 响应抛 AiException。 */
	@Test
	public void testErrorStatusThrows() {
		this.server.createContext("/v1/graphql", ex -> respond(ex, 401, "{\"error\":\"x\"}"));
		WeaviateVectorStore store = baseBuilder().build();
		assertThrows(AiException.class,
				() -> store.similaritySearch(new float[] { 0.1f, 0.2f }, 1));
	}

	/** 带 filter 检索：GraphQL variables.where 含翻译后的 where 对象。 */
	@Test
	public void testSearchWithFilter() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/v1/graphql", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"data\":{\"Get\":{\"" + CLASS + "\":[]}}}");
		});
		WeaviateVectorStore store = baseBuilder().build();
		store.similaritySearch(new float[] { 0.1f, 0.2f }, 2,
				FilterExpression.eq("tenant", "acme"));
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		JsonObject where = body.getJsonObject("variables").getJsonObject("where");
		assertEquals("Equal", where.getString("operator"));
		assertEquals("acme", where.getString("valueText"));
		assertEquals("tenant", where.getJsonArray("path").getString(0));
	}

	/** size：GraphQL Aggregate meta.count。 */
	@Test
	public void testSize() {
		this.server.createContext("/v1/graphql", ex -> respond(ex, 200,
				"{\"data\":{\"Aggregate\":{\"" + CLASS + "\":[{\"meta\":{\"count\":3}}]}}}"));
		WeaviateVectorStore store = baseBuilder().build();
		assertEquals(3, store.size());
	}
}
