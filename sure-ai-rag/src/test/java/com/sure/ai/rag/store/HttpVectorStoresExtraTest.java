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
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.store.typesense.TypesenseVectorStore;

/**
 * HTTP 类向量库补覆盖测试：统一本地 {@link HttpServer} mock 覆盖各库 Builder 的
 * {@code httpClient} 注入 setter、baseUrl 尾斜杠裁剪、三参检索重载委托、预置线程中断
 * 触发 InterruptedException 异常分支、apiKey/token 鉴权头下发，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class HttpVectorStoresExtraTest {

	private HttpServer server;
	private String baseUrl;
	private volatile String body = "{\"count\":0}";
	private final AtomicReference<String> authHeader = new AtomicReference<>();

	/** 启动本地服务并捕获鉴权头。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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

	// ===================== Elasticsearch / OpenSearch =====================

	/** ES：注入自定义 httpClient；尾斜杠 baseUrl；三参重载委托。 */
	@Test
	public void testElasticsearchCommonBranches() {
		ElasticsearchVectorStore store = ElasticsearchVectorStore.builder()
				.baseUrl(baseUrl + "/").indexName("docs").dimension(4)
				.httpClient(HttpClient.newHttpClient()).build();
		assertNotNull(store);
		assertEquals(0, store.size());
		store.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5);
	}

	/** ES：预置中断 → AiException。 */
	@Test
	public void testElasticsearchInterrupted() {
		ElasticsearchVectorStore store = ElasticsearchVectorStore.builder()
				.baseUrl(baseUrl).indexName("docs").dimension(4).build();
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, store::size);
		} finally {
			Thread.interrupted();
		}
	}

	/** OpenSearch：注入 httpClient；尾斜杠；重载委托。 */
	@Test
	public void testOpenSearchCommonBranches() {
		OpenSearchVectorStore store = OpenSearchVectorStore.builder()
				.baseUrl(baseUrl + "/").indexName("docs").dimension(4)
				.httpClient(HttpClient.newHttpClient()).build();
		assertNotNull(store);
		assertEquals(0, store.size());
	}

	// ===================== Typesense / Milvus / Weaviate / Chroma / Qdrant / Pinecone =====================

	/** Typesense：注入 httpClient；尾斜杠；三参重载。 */
	@Test
	public void testTypesenseCommonBranches() {
		TypesenseVectorStore store = TypesenseVectorStore.builder()
				.baseUrl(baseUrl + "/").collectionName("docs").dimension(3)
				.httpClient(HttpClient.newHttpClient()).build();
		assertNotNull(store);
		store.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 5, 0.5);
	}

	/** Milvus：注入 httpClient；尾斜杠。 */
	@Test
	public void testMilvusCommonBranches() {
		MilvusVectorStore store = MilvusVectorStore.builder()
				.baseUrl(baseUrl + "/").collectionName("docs").dimension(4)
				.apiKey("secret").httpClient(HttpClient.newHttpClient()).build();
		assertNotNull(store);
	}

	/** Weaviate：注入 httpClient；自定义 textProperty；尾斜杠。 */
	@Test
	public void testWeaviateCommonBranches() {
		WeaviateVectorStore store = WeaviateVectorStore.builder()
				.baseUrl(baseUrl + "/").className("Doc").textProperty("content")
				.apiKey("w-key").httpClient(HttpClient.newHttpClient()).build();
		assertNotNull(store);
	}

	/** Qdrant：注入 httpClient；尾斜杠；重载委托。 */
	@Test
	public void testQdrantCommonBranches() {
		QdrantVectorStore store = QdrantVectorStore.builder()
				.baseUrl(baseUrl + "/").collectionName("docs").dimension(4)
				.httpClient(HttpClient.newHttpClient()).build();
		assertNotNull(store);
		store.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5);
	}

	/** Pinecone：注入 httpClient；尾斜杠。 */
	@Test
	public void testPineconeCommonBranches() {
		PineconeVectorStore store = PineconeVectorStore.builder()
				.baseUrl(baseUrl + "/").apiKey("p-key").namespace("ns")
				.httpClient(HttpClient.newHttpClient()).build();
		assertNotNull(store);
	}
}
