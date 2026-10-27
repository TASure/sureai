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
 * {@link OpenSearchVectorStore} 错误/边界分支单元测试：本地 {@link HttpServer} mock 覆盖
 * 空批量/null、delete、size、minScore 过滤、_source/vector 缺失、建索引校验、错误状态、
 * 连接拒绝，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class OpenSearchVectorStoreDeepTest {

	private HttpServer server;
	private String baseUrl;
	private volatile int status = 200;
	private volatile String body = "{\"count\":1}";

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

	private OpenSearchVectorStore.Builder baseBuilder() {
		return OpenSearchVectorStore.builder().baseUrl(baseUrl).indexName("docs")
				.vectorField("vector").textField("text").dimension(4);
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

	/** autoCreateIndex：构造期建索引。 */
	@Test
	public void testAutoCreateIndex() {
		this.body = "{\"acknowledged\":true}";
		baseBuilder().autoCreateIndex(true).build();
	}

	/** clear：DELETE 索引。 */
	@Test
	public void testClear() {
		this.body = "{}";
		baseBuilder().build().clear();
	}

	/** delete 返回 true。 */
	@Test
	public void testDelete() {
		assertTrue(baseBuilder().build().delete("v1"));
	}

	/** size 取 count。 */
	@Test
	public void testSize() {
		this.body = "{\"count\":7}";
		assertEquals(7, baseBuilder().build().size());
	}

	/** 两参检索重载委托。 */
	@Test
	public void testSearchOverload() {
		this.body = "{\"hits\":{\"hits\":[{\"_id\":\"v\",\"_score\":0.9,\"_source\":{\"text\":\"t\",\"vector\":[0.1]}}]}}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f }, 5, 0.5);
		assertEquals(1, r.size());
	}

	/** 尾部斜杠 baseUrl；_source 含非字符串元数据被过滤。 */
	@Test
	public void testTrailingSlashAndNonStringMeta() {
		this.body = "{\"hits\":{\"hits\":[{\"_id\":\"v\",\"_score\":0.9,"
				+ "\"_source\":{\"text\":\"t\",\"vector\":[0.1],\"num\":1,\"flag\":true}}]}}";
		OpenSearchVectorStore store = OpenSearchVectorStore.builder()
				.baseUrl(baseUrl + "/").indexName("docs").dimension(4).build();
		List<SimilaritySearchResult> r = store.similaritySearch(new float[] { 0.1f }, 5);
		assertEquals(1, r.size());
		assertTrue(r.get(0).metadata().isEmpty());
	}

	/** 检索：minScore 过滤；_source/vector 缺失降级。 */
	@Test
	public void testSearchEdge() {
		this.body = "{\"hits\":{\"hits\":["
				+ "{\"_id\":\"low\",\"_score\":0.1,\"_source\":{\"text\":\"a\"}},"
				+ "{\"_id\":\"high\",\"_score\":0.9,\"_source\":{\"text\":\"b\",\"vector\":[0.1,0.2]}}"
				+ "]}}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("high", r.get(0).id());
	}

	/** 建索引缺 dimension → AiException。 */
	@Test
	public void testCreateIndexNoDimensionThrows() {
		assertThrows(AiException.class, () -> OpenSearchVectorStore.builder()
				.baseUrl(baseUrl).indexName("docs").build().createIndex());
	}

	/** 错误状态 → AiException。 */
	@Test
	public void testErrorStatusThrows() {
		this.status = 500;
		assertThrows(AiException.class, () -> baseBuilder().build().addAll(List.of(v("v1"))));
	}

	/** 构建器全量 fluent setter。 */
	@Test
	public void testBuilderSetters() {
		this.body = "{}";
		OpenSearchVectorStore.builder().baseUrl(baseUrl).apiKey("k").indexName("docs")
				.vectorField("vec").textField("txt").dimension(4).autoCreateIndex(true)
				.timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** 连接拒绝 → AiException。 */
	@Test
	public void testConnectionRefusedThrows() throws IOException {
		try (java.net.ServerSocket dead = new java.net.ServerSocket(0)) {
			int port = dead.getLocalPort();
			server.stop(0);
			OpenSearchVectorStore store = OpenSearchVectorStore.builder()
					.baseUrl("http://127.0.0.1:" + port).indexName("docs").dimension(4).build();
			assertThrows(AiException.class, store::size);
		}
	}
}
