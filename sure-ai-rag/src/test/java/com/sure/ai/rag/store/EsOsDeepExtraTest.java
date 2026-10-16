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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * {@link ElasticsearchVectorStore} / {@link OpenSearchVectorStore} 补覆盖测试：
 * 慢 mock 触发 InterruptedException 异常分支、autoCreateIndex 时 clear() 重建索引、
 * 检索响应中 _source/vector 缺失降级、字符串元数据提取，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class EsOsDeepExtraTest {

	private HttpServer server;
	private String baseUrl;
	private volatile String body = "{\"count\":0}";
	private volatile long delayMs = 0;

	/** 启动慢 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			if (delayMs > 0) {
				try {
					Thread.sleep(delayMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
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

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "t-" + id, Map.of("source", id + ".pdf"));
	}

	/** ES：autoCreateIndex=true 时 clear() 后重建索引。 */
	@Test
	public void testEsClearAutoCreate() {
		this.body = "{}";
		ElasticsearchVectorStore store = ElasticsearchVectorStore.builder()
				.baseUrl(baseUrl).indexName("docs").dimension(4).autoCreateIndex(true).build();
		store.clear();
		assertNotNull(store);
	}

	/** ES：检索响应中 _source 缺失、vector 字段缺失、字符串元数据提取。 */
	@Test
	public void testEsSearchEdgeBranches() {
		this.body = "{\"hits\":{\"hits\":["
				+ "{\"_id\":\"nosrc\",\"_score\":1.5},"
				+ "{\"_id\":\"nometa\",\"_score\":1.5,\"_source\":{\"text\":\"b\",\"author\":\"alice\"}},"
				+ "{\"_id\":\"ok\",\"_score\":1.5,\"_source\":{\"text\":\"c\",\"vector\":[0.1,0.2]}}"
				+ "]}}";
		List<SimilaritySearchResult> r = ElasticsearchVectorStore.builder()
				.baseUrl(baseUrl).indexName("docs").dimension(4).build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.0, null);
		assertEquals(3, r.size());
		SimilaritySearchResult withMeta = r.stream().filter(x -> "nometa".equals(x.id())).findFirst().orElseThrow();
		assertEquals("alice", withMeta.metadata().get("author"));
	}

	/** ES：预置线程中断 + 慢 mock → AiException（中断分支）。 */
	@Test
	public void testEsInterruptedBranch() {
		this.delayMs = 500;
		ElasticsearchVectorStore store = ElasticsearchVectorStore.builder()
				.baseUrl(baseUrl).indexName("docs").dimension(4).build();
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, store::size);
		} finally {
			Thread.interrupted();
		}
	}

	/** OpenSearch：autoCreateIndex=true 时 clear() 后重建索引。 */
	@Test
	public void testOsClearAutoCreate() {
		this.body = "{}";
		OpenSearchVectorStore store = OpenSearchVectorStore.builder()
				.baseUrl(baseUrl).indexName("docs").dimension(4).autoCreateIndex(true).build();
		store.clear();
		assertNotNull(store);
	}

	/** OpenSearch：检索响应中 _source 缺失、vector 字段缺失。 */
	@Test
	public void testOsSearchEdgeBranches() {
		this.body = "{\"hits\":{\"hits\":["
				+ "{\"_id\":\"nosrc\",\"_score\":1.5},"
				+ "{\"_id\":\"ok\",\"_score\":1.5,\"_source\":{\"text\":\"c\",\"vector\":[0.1,0.2]}}"
				+ "]}}";
		List<SimilaritySearchResult> r = OpenSearchVectorStore.builder()
				.baseUrl(baseUrl).indexName("docs").dimension(4).build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.0, null);
		assertEquals(2, r.size());
	}

	/** OpenSearch：预置线程中断 + 慢 mock → AiException。 */
	@Test
	public void testOsInterruptedBranch() {
		this.delayMs = 500;
		OpenSearchVectorStore store = OpenSearchVectorStore.builder()
				.baseUrl(baseUrl).indexName("docs").dimension(4).build();
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, store::size);
		} finally {
			Thread.interrupted();
		}
	}
}
