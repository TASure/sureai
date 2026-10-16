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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;

/**
 * {@link ChromaVectorStore} 错误/边界分支单元测试：本地 {@link HttpServer} mock 覆盖
 * get-or-create（200/404 自动建）、空批量/null、L2/COSINE 得分映射、minScore 过滤、
 * 缺失字段降级、错误状态、连接拒绝，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class ChromaVectorStoreDeepTest {

	private HttpServer server;
	private String baseUrl;
	private volatile int getStatus = 200;
	private volatile String getBody = "{\"id\":\"col-1\"}";
	private volatile int status = 200;
	private volatile String body = "{}";

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			int useStatus = "GET".equals(ex.getRequestMethod()) ? getStatus : status;
			String useBody = "GET".equals(ex.getRequestMethod()) ? getBody : body;
			byte[] bytes = useBody.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(useStatus, bytes.length == 0 ? -1 : bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				if (bytes.length > 0) {
					os.write(bytes);
				}
			}
			ex.close();
		});
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	private ChromaVectorStore.Builder baseBuilder() {
		return ChromaVectorStore.builder().baseUrl(baseUrl).collectionName("docs");
	}

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "t-" + id, Map.of("source", id + ".pdf"));
	}

	/** 空批量/null；add(Vector) 委托。 */
	@Test
	public void testAddAllEmptyAndNull() {
		baseBuilder().build().addAll(new ArrayList<>());
		baseBuilder().build().add(v("v1"));
		baseBuilder().build().addAll(new ArrayList<>(Arrays.asList(v("v1"), null)));
	}

	/** clear：DELETE 集合后自动重建。 */
	@Test
	public void testClear() {
		baseBuilder().build().clear();
	}

	/** 构建器全量 setter。 */
	@Test
	public void testBuilderSetters() {
		baseBuilder().token("t").autoCreateCollection(false)
				.timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** size 固定 -1。 */
	@Test
	public void testSize() {
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 检索：L2 得分映射；minScore 过滤；缺失字段降级。 */
	@Test
	public void testSearchL2() {
		this.body = "{\"ids\":[[\"low\",\"high\"]],"
				+ "\"distances\":[[2.0,0.1]],"
				+ "\"documents\":[[\"a\",\"b\"]],"
				+ "\"metadatas\":[[{\"source\":\"a\"},{\"source\":\"b\"}]],"
				+ "\"embeddings\":[[[0.1,0.2],[0.3,0.4]]]}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("high", r.get(0).id());
	}

	/** COSINE 得分映射 1-distance。 */
	@Test
	public void testSearchCosine() {
		this.body = "{\"ids\":[[\"v\"]],\"distances\":[[0.1]],"
				+ "\"documents\":[[\"x\"]],\"metadatas\":[[]],\"embeddings\":[[]]}";
		List<SimilaritySearchResult> r = baseBuilder().distanceFunction(ChromaVectorStore.DistanceFunction.COSINE)
				.build().similaritySearch(new float[] { 0.1f }, 1);
		assertEquals(0.9, r.get(0).score(), 1e-9);
	}

	/** 404 自动建集合。 */
	@Test
	public void testAutoCreateOn404() {
		this.getStatus = 404;
		this.body = "{\"id\":\"new-col\"}";
		ChromaVectorStore store = baseBuilder().autoCreateCollection(true).build();
		assertEquals(-1, store.size());
	}

	/** 错误状态 → AiException。 */
	@Test
	public void testErrorStatusThrows() {
		this.status = 500;
		assertThrows(AiException.class, () -> baseBuilder().build().addAll(List.of(v("v1"))));
	}

	/** 连接拒绝 → AiException。 */
	@Test
	public void testConnectionRefusedThrows() throws IOException {
		try (java.net.ServerSocket dead = new java.net.ServerSocket(0)) {
			int port = dead.getLocalPort();
			server.stop(0);
			assertThrows(AiException.class, () -> ChromaVectorStore.builder()
					.baseUrl("http://127.0.0.1:" + port).collectionName("docs").build());
		}
	}
}
