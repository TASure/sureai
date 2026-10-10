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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
 * {@link WeaviateVectorStore} 错误/边界分支单元测试：本地 {@link HttpServer} mock 覆盖
 * 空批量/null、delete 状态码、size 各缺失路径、certainty/distance 得分映射、minScore 过滤、
 * 自动建 schema、错误状态、连接拒绝，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class WeaviateVectorStoreDeepTest {

	private static final String CLAZZ = "Doc";

	private HttpServer server;
	private String baseUrl;
	private volatile int status = 200;
	private volatile String body = "{}";

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
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

	private WeaviateVectorStore.Builder baseBuilder() {
		return WeaviateVectorStore.builder().baseUrl(baseUrl).className(CLAZZ);
	}

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "t-" + id, Map.of("source", id + ".pdf"));
	}

	/** 空批量/null。 */
	@Test
	public void testAddAllEmptyAndNull() {
		baseBuilder().build().addAll(new ArrayList<>());
		baseBuilder().build().add(v("v1"));
		baseBuilder().build().addAll(new ArrayList<>(Arrays.asList(v("v1"), null)));
	}

	/** 构建器全量 setter。 */
	@Test
	public void testBuilderSetters() {
		baseBuilder().apiKey("k").autoCreateSchema(false)
				.timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** delete：2xx true；5xx false。 */
	@Test
	public void testDeleteStatus() {
		assertTrue(baseBuilder().build().delete("v1"));
		this.status = 500;
		assertFalse(baseBuilder().build().delete("v1"));
	}

	/** size：data/Aggregate/meta 缺失 → -1；正常取 count。 */
	@Test
	public void testSizeEdge() {
		this.body = "{}";
		assertEquals(-1, baseBuilder().build().size());
		this.body = "{\"data\":{\"Aggregate\":{\"" + CLAZZ + "\":[{\"meta\":{\"count\":9}}]}}}";
		assertEquals(9, baseBuilder().build().size());
	}

	/** 检索：distance 映射得分；minScore 过滤；certainty 优先。 */
	@Test
	public void testSearchScore() {
		this.body = "{\"data\":{\"Get\":{\"" + CLAZZ + "\":["
				+ "{\"text\":\"low\",\"_doc_id\":\"a\",\"_additional\":{\"distance\":2.0,\"id\":\"x\"}},"
				+ "{\"text\":\"high\",\"_doc_id\":\"b\",\"_additional\":{\"certainty\":0.9,\"id\":\"y\"}}"
				+ "]}}}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("b", r.get(0).id());
	}

	/** 自动建 schema 不抛。 */
	@Test
	public void testAutoCreateSchema() {
		this.body = "{}";
		baseBuilder().autoCreateSchema(true).build();
	}

	/** 错误状态 → AiException。 */
	@Test
	public void testErrorStatusThrows() {
		this.status = 500;
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 连接拒绝 → AiException。 */
	@Test
	public void testConnectionRefusedThrows() throws IOException {
		try (java.net.ServerSocket dead = new java.net.ServerSocket(0)) {
			int port = dead.getLocalPort();
			server.stop(0);
			WeaviateVectorStore store = WeaviateVectorStore.builder()
					.baseUrl("http://127.0.0.1:" + port).className(CLAZZ).build();
			assertThrows(AiException.class, store::size);
		}
	}
}
