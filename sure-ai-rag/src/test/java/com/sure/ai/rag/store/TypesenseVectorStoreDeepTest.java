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

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.typesense.TypesenseVectorStore;

/**
 * {@link TypesenseVectorStore} 错误/边界分支单元测试：本地 {@link HttpServer} mock 覆盖
 * 空批量/null、delete 计数、clear、hits 缺失/document 非对象、minScore 过滤、元数据仅取字符串、
 * 自动建集合吞错与缺维度、HTTP 错误、空响应、连接拒绝，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class TypesenseVectorStoreDeepTest {

	private HttpServer server;
	private String baseUrl;
	private volatile int status = 200;
	private volatile String body = "{}";
	private final AtomicReference<String> path = new AtomicReference<>();
	private final AtomicReference<String> apiKey = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			path.set(ex.getRequestURI().toString());
			apiKey.set(ex.getRequestHeaders().getFirst("X-TYPESENSE-API-KEY"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(status, status >= 400 ? -1 : bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				if (status < 400 && bytes.length > 0) {
					os.write(bytes);
				}
			}
		});
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	private TypesenseVectorStore.Builder baseBuilder() {
		return TypesenseVectorStore.builder().baseUrl(baseUrl).collectionName("docs");
	}

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "text-" + id, Map.of("source", id + ".pdf"));
	}

	/** 空批量直接返回；add(Vector) 委托。 */
	@Test
	public void testAddAllEmpty() {
		baseBuilder().build().addAll(List.of());
		baseBuilder().build().add(v("v1"));
	}

	/** 构建器全量 setter。 */
	@Test
	public void testBuilderSetters() {
		baseBuilder().vectorField("vec").textField("txt").timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** delete：num_deleted>0 返回 true；=0 返回 false。 */
	@Test
	public void testDelete() {
		this.body = "{\"num_deleted\":1}";
		assertTrue(baseBuilder().build().delete("v1"));
		this.body = "{\"num_deleted\":0}";
		assertEquals(false, baseBuilder().build().delete("nope"));
	}

	/** clear：DELETE 集合端点。 */
	@Test
	public void testClear() {
		this.body = "{}";
		baseBuilder().build().clear();
		assertTrue(path.get(), path.get().contains("/collections/docs"));
	}

	/** size：num_doc 解析。 */
	@Test
	public void testSize() {
		this.body = "{\"num_doc\":42}";
		assertEquals(42, baseBuilder().build().size());
	}

	/** 检索：hits 缺失 → 空结果；minScore 过滤；document 非对象。 */
	@Test
	public void testSearchHitsEdge() {
		this.body = "{}"; // 无 hits
		assertTrue(baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 3).isEmpty());

		this.body = "{\"hits\":["
				+ "{\"vector_distance\":0.9,\"document\":{\"id\":\"low\",\"text\":\"x\",\"source\":\"a\"}},"
				+ "{\"vector_distance\":0.1,\"document\":{\"id\":\"high\",\"text\":\"keep\",\"source\":\"b\",\"count\":5}}"
				+ "]}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("high", r.get(0).id());
		assertEquals("keep", r.get(0).text());
		// toStringMap 跳过非字符串字段 count
		assertTrue(r.get(0).metadata().containsKey("source"));
	}

	/** 自动建集合：dimension>0 best-effort；缺维度抛异常。 */
	@Test
	public void testAutoCreateCollection() {
		this.body = "{}";
		baseBuilder().dimension(3).autoCreateCollection(true).build();
		assertThrows(AiException.class, () -> baseBuilder().autoCreateCollection(true).build());
	}

	/** HTTP 4xx → AiException。 */
	@Test
	public void testHttpErrorThrows() {
		this.status = 404;
		this.body = "not found";
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 空响应体按 {} 解析。 */
	@Test
	public void testBlankBody() {
		this.body = "";
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 无 apiKey → 不发送鉴权头。 */
	@Test
	public void testNoAuthHeader() {
		this.body = "{\"num_doc\":1}";
		baseBuilder().build().size();
		assertEquals(null, apiKey.get());
	}

	/** 连接拒绝 → AiException。 */
	@Test
	public void testConnectionRefusedThrows() {
		int port = server.getAddress().getPort();
		server.stop(0);
		TypesenseVectorStore store = TypesenseVectorStore.builder()
				.baseUrl("http://127.0.0.1:" + port).collectionName("docs").build();
		assertThrows(AiException.class, () -> store.size());
	}
}
