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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;

/**
 * {@link ChromaVectorStore} 单元测试：本地 {@link HttpServer} mock，零真实网络。
 *
 * @author sureai
 * @since 1.1.0
 */
public class ChromaVectorStoreTest {

	private static final String COLLECTION = "test-set";

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

	private ChromaVectorStore.Builder baseBuilder() {
		return ChromaVectorStore.builder()
				.baseUrl(baseUrl)
				.collectionName(COLLECTION)
				.distanceFunction(ChromaVectorStore.DistanceFunction.L2);
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
		return Vector.of(id, new float[] { 0.1f, 0.2f, 0.3f, 0.4f }, "text-" + id,
				Map.of("source", id + ".pdf"));
	}

	/** 集合已存在：GET 200，缓存 collection_id。 */
	private void stubExistingCollection() {
		this.server.createContext("/api/v1/collections/" + COLLECTION,
				ex -> respond(ex, 200, "{\"id\":\"cid-1\",\"name\":\"" + COLLECTION + "\"}"));
	}

	/** get-or-create：GET 404 后 POST 创建，两个请求都发出且 body 正确。 */
	@Test
	public void testGetOrCreateCollection() {
		AtomicInteger getCount = new AtomicInteger();
		AtomicReference<String> createBody = new AtomicReference<>();
		this.server.createContext("/api/v1/collections/" + COLLECTION, ex -> {
			getCount.incrementAndGet();
			respond(ex, 404, "{\"detail\":\"Not Found\"}");
		});
		this.server.createContext("/api/v1/collections", ex -> {
			createBody.set(readBody(ex));
			respond(ex, 200, "{\"id\":\"cid-new\",\"name\":\"" + COLLECTION + "\"}");
		});
		ChromaVectorStore store = baseBuilder().build();
		assertEquals(1, getCount.get());
		JsonObject body = (JsonObject) Json.parse(createBody.get());
		assertEquals(COLLECTION, body.getString("name"));
		assertEquals("l2", body.getJsonObject("metadata").getString("hnsw:space"));
		assertTrue(store.size() == -1);
	}

	/** 添加 + 查询：返回正确 id/text，L2 距离映射为 1/(1+distance)。 */
	@Test
	public void testAddAndQuery() {
		stubExistingCollection();
		this.server.createContext("/api/v1/collections/cid-1/add",
				ex -> respond(ex, 200, ""));
		this.server.createContext("/api/v1/collections/cid-1/query", ex -> respond(ex, 200,
				"{\"ids\":[[\"v1\",\"v2\"]],"
						+ "\"distances\":[[0.5,0.1]],"
						+ "\"documents\":[[\"text-v1\",\"text-v2\"]],"
						+ "\"metadatas\":[[{\"source\":\"v1.pdf\"},{}]],"
						+ "\"embeddings\":[[[0.1,0.2,0.3,0.4],[0.5,0.6,0.7,0.8]]]}"));
		ChromaVectorStore store = baseBuilder().build();
		store.addAll(List.of(sampleVector("v1"), sampleVector("v2")));
		List<SimilaritySearchResult> results = store
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f, 0.4f }, 2);
		// 按相似度降序：v2(0.1→0.909) 在前，v1(0.5→0.667) 在后
		assertEquals(2, results.size());
		assertEquals("v2", results.get(0).id());
		assertEquals(1d / 1.1d, results.get(0).score(), 1e-9);
		assertEquals("v1", results.get(1).id());
		assertEquals("text-v1", results.get(1).text());
		assertEquals("v1.pdf", results.get(1).metadata().get("source"));
		assertEquals(1d / 1.5d, results.get(1).score(), 1e-9);
	}

	/** L2 距离 0.5 → 相似度 1/(1+0.5)≈0.6667。 */
	@Test
	public void testDistanceMapping() {
		stubExistingCollection();
		this.server.createContext("/api/v1/collections/cid-1/query", ex -> respond(ex, 200,
				"{\"ids\":[[\"only\"]],\"distances\":[[0.5]],"
						+ "\"documents\":[[\"x\"]],\"metadatas\":[[]],\"embeddings\":[[]]}"));
		ChromaVectorStore store = baseBuilder().build();
		List<SimilaritySearchResult> results = store
				.similaritySearch(new float[] { 0.1f, 0.2f }, 1);
		assertEquals(1, results.size());
		assertEquals(1d / 1.5d, results.get(0).score(), 1e-9);
	}

	/** 删除成功返回 true。 */
	@Test
	public void testDelete() {
		stubExistingCollection();
		this.server.createContext("/api/v1/collections/cid-1/delete",
				ex -> respond(ex, 200, ""));
		ChromaVectorStore store = baseBuilder().build();
		assertTrue(store.delete("v1"));
	}
}
