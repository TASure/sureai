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

/**
 * {@link MilvusVectorStore} 单元测试：本地 {@link HttpServer} mock，零真实网络。
 *
 * @author sureai
 * @since 1.1.0
 */
public class MilvusVectorStoreTest {

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

	private MilvusVectorStore.Builder baseBuilder() {
		return MilvusVectorStore.builder()
				.baseUrl(baseUrl)
				.collectionName("docs")
				.dimension(4)
				.metricType(MilvusVectorStore.MetricType.COSINE);
	}

	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static String readBody(HttpExchange ex) throws IOException {
		return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
	}

	private static Vector sampleVector(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f, 0.3f, 0.4f }, "text-" + id,
				Map.of("source", id + ".pdf"));
	}

	/** 构造时自动创建集合，请求体含 collectionName/dimension/metricType。 */
	@Test
	public void testAutoCreateCollection() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/v2/vectordb/collections/create", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"code\":0,\"data\":{}}");
		});
		baseBuilder().build();
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals("docs", body.getString("collectionName"));
		assertEquals(4, body.getInt("dimension"));
		assertEquals("COSINE", body.getString("metricType"));
	}

	/** 写入 + 检索：addAll 不抛异常，similaritySearch 返回正确 id/text/score。 */
	@Test
	public void testAddAndSearch() {
		this.server.createContext("/v2/vectordb/collections/create",
				ex -> respond(ex, 200, "{\"code\":0}"));
		this.server.createContext("/v2/vectordb/entities/insert",
				ex -> respond(ex, 200, "{\"code\":0,\"data\":{\"insertCount\":2}}"));
		this.server.createContext("/v2/vectordb/entities/search", ex -> respond(ex, 200,
				"{\"code\":0,\"data\":["
						+ "{\"id\":\"v1\",\"distance\":0.9,\"text\":\"text-v1\","
						+ "\"vector\":[0.1,0.2,0.3,0.4],\"source\":\"v1.pdf\"},"
						+ "{\"id\":\"v2\",\"distance\":0.5,\"text\":\"text-v2\","
						+ "\"vector\":[0.5,0.6,0.7,0.8],\"source\":\"v2.pdf\"}"
						+ "]}"));
		MilvusVectorStore store = baseBuilder().build();
		store.addAll(List.of(sampleVector("v1"), sampleVector("v2")));
		List<SimilaritySearchResult> results = store
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f, 0.4f }, 2);
		assertEquals(2, results.size());
		assertEquals("v1", results.get(0).id());
		assertEquals("text-v1", results.get(0).text());
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("v1.pdf", results.get(0).metadata().get("source"));
		assertNotNull(results.get(0).embedding());
		assertEquals(4, results.get(0).embedding().length);
	}

	/** 检索请求体含 data(查询向量)/limit/outputFields。 */
	@Test
	public void testSearchRequestBody() {
		this.server.createContext("/v2/vectordb/collections/create",
				ex -> respond(ex, 200, "{\"code\":0}"));
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/v2/vectordb/entities/search", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"code\":0,\"data\":[]}");
		});
		MilvusVectorStore store = baseBuilder().build();
		store.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f, 0.4f }, 3);
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals("docs", body.getString("collectionName"));
		assertEquals(3, body.getInt("limit"));
		assertEquals(1, body.getJsonArray("data").size());
		assertEquals(4, body.getJsonArray("data").getJsonArray(0).size());
		assertTrue(body.getJsonArray("outputFields").toString().contains("text"));
	}

	/** 删除返回 code=0 视为成功。 */
	@Test
	public void testDelete() {
		this.server.createContext("/v2/vectordb/collections/create",
				ex -> respond(ex, 200, "{\"code\":0}"));
		this.server.createContext("/v2/vectordb/entities/delete",
				ex -> respond(ex, 200, "{\"code\":0,\"data\":{\"deleteCount\":1}}"));
		MilvusVectorStore store = baseBuilder().build();
		assertTrue(store.delete("v1"));
	}

	/** P0-3: 恶意 id 中的双引号必须被转义，不能逃逸字符串字面量。 */
	@Test
	public void testDeleteFilterEscapesInjection() {
		this.server.createContext("/v2/vectordb/collections/create",
				ex -> respond(ex, 200, "{\"code\":0}"));
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/v2/vectordb/entities/delete", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"code\":0,\"data\":{\"deleteCount\":1}}");
		});
		MilvusVectorStore store = baseBuilder().build();
		// 恶意 id：试图闭合字符串并注入 " or "1"="1
		store.delete("x\" or \"1\"=\"1\"");
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		// 转义后 filter 应为 id in ["x\" or \"1\"=\"1\""]（双引号被反斜杠转义）
		assertEquals("id in [\"x\\\" or \\\"1\\\"=\\\"1\\\"\"]", body.getString("filter"));
		// 绝不能出现未转义的注入形式
		assertTrue("filter 不应包含未转义的注入片段",
				!body.getString("filter").contains("\"x\" or \"1\"=\"1\""));
	}

	/** P0-3: id 中的反斜杠必须被转义为双反斜杠。 */
	@Test
	public void testDeleteFilterEscapesBackslash() {
		this.server.createContext("/v2/vectordb/collections/create",
				ex -> respond(ex, 200, "{\"code\":0}"));
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/v2/vectordb/entities/delete", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"code\":0,\"data\":{\"deleteCount\":1}}");
		});
		MilvusVectorStore store = baseBuilder().build();
		store.delete("test\\id");
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		// 反斜杠应转义为双反斜杠
		assertEquals("id in [\"test\\\\id\"]", body.getString("filter"));
	}

	/** P0-3: 普通 id 不包含特殊字符时行为不变。 */
	@Test
	public void testDeleteFilterNormalIdUnchanged() {
		this.server.createContext("/v2/vectordb/collections/create",
				ex -> respond(ex, 200, "{\"code\":0}"));
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/v2/vectordb/entities/delete", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, 200, "{\"code\":0,\"data\":{\"deleteCount\":1}}");
		});
		MilvusVectorStore store = baseBuilder().build();
		store.delete("normal-id_123");
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		assertEquals("id in [\"normal-id_123\"]", body.getString("filter"));
	}

	/** HTTP 500 包装为 AiException。 */
	@Test
	public void testErrorResponse() {
		this.server.createContext("/v2/vectordb/collections/create",
				ex -> respond(ex, 200, "{\"code\":0}"));
		this.server.createContext("/v2/vectordb/entities/insert",
				ex -> respond(ex, 500, "{\"error\":\"boom\"}"));
		MilvusVectorStore store = baseBuilder().build();
		assertThrows(AiException.class,
				() -> store.addAll(List.of(sampleVector("v1"))));
	}
}
