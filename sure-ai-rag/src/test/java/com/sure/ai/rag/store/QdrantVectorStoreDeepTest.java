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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.exception.AiException;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;

/**
 * {@link QdrantVectorStore} 错误/边界分支单元测试：本地 {@link HttpServer} mock 覆盖
 * 空批量/null、size 异常、minScore 过滤、payload/vector 缺失、建集合校验、空响应、
 * 空 apiKey、欧氏距离映射、clear 重建、连接拒绝，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class QdrantVectorStoreDeepTest {

	private static final String COLLECTION = "test-collection";

	private HttpServer server;
	private String baseUrl;
	private volatile String body = "{\"result\":{\"count\":1},\"status\":\"ok\"}";
	private final AtomicReference<String> lastPath = new AtomicReference<>();
	private final AtomicReference<String> apiKey = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			lastPath.set(ex.getRequestURI().getPath());
			apiKey.set(ex.getRequestHeaders().getFirst("api-key"));
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

	private QdrantVectorStore.Builder baseBuilder() {
		return QdrantVectorStore.builder().baseUrl(baseUrl).collectionName(COLLECTION);
	}

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "t-" + id, Map.of("source", id + ".pdf"));
	}

	/** 空批量直接返回；null 元素跳过。 */
	@Test
	public void testAddAllEmptyAndNull() {
		baseBuilder().build().addAll(new ArrayList<>());
		baseBuilder().build().add(v("v1"));
		baseBuilder().build().addAll(new ArrayList<>(Arrays.asList(v("v1"), null)));
	}

	/** 构建器全量 setter。 */
	@Test
	public void testBuilderSetters() {
		baseBuilder().apiKey("k").autoCreateCollection(false)
				.timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** size：dimension<=0 返回 -1；result 非对象返回 -1。 */
	@Test
	public void testSizeEdge() {
		assertEquals(-1, baseBuilder().build().size());
		this.body = "{\"result\":[1,2]}";
		assertEquals(-1, baseBuilder().dimension(4).build().size());
	}

	/** 检索：minScore 过滤；payload/vector 缺失时降级。 */
	@Test
	public void testSearchEdge() {
		this.body = "{\"result\":["
				+ "{\"id\":\"low\",\"score\":0.1},"
				+ "{\"id\":\"high\",\"score\":0.9,\"payload\":{\"text\":\"x\",\"source\":\"a\"}}"
				+ "]}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("high", r.get(0).id());
		assertEquals("a", r.get(0).metadata().get("source"));
	}

	/** 欧氏距离映射为 1/(1+|d|)。 */
	@Test
	public void testEuclidScore() {
		this.body = "{\"result\":[{\"id\":\"v\",\"score\":1.0}]}";
		List<SimilaritySearchResult> r = baseBuilder().distance(QdrantVectorStore.Distance.EUCLID)
				.build().similaritySearch(new float[] { 0.1f }, 1);
		assertEquals(0.5, r.get(0).score(), 1e-9);
	}

	/** 建集合缺 dimension → AiException。 */
	@Test
	public void testCreateCollectionNoDimensionThrows() {
		assertThrows(AiException.class, () -> baseBuilder().build().createCollection());
	}

	/** 空响应体按 {} 解析。 */
	@Test
	public void testBlankBody() {
		this.body = "";
		assertEquals(-1, baseBuilder().dimension(4).build().size());
	}

	/** 空 apiKey → 不发送鉴权头。 */
	@Test
	public void testBlankApiKeyNoHeader() {
		this.body = "{\"result\":{\"count\":1}}";
		baseBuilder().apiKey(" ").build().size();
		assertEquals(null, apiKey.get());
	}

	/** clear 开启 autoCreate：删除后重建集合。 */
	@Test
	public void testClearRecreates() {
		this.body = "{\"result\":true}";
		baseBuilder().dimension(3).autoCreateCollection(true).build().clear();
		assertTrue(lastPath.get().contains(COLLECTION));
	}

	/** 连接拒绝 → AiException。 */
	@Test
	public void testConnectionRefusedThrows() throws IOException {
		try (java.net.ServerSocket dead = new java.net.ServerSocket(0)) {
			int port = dead.getLocalPort();
			server.stop(0);
			QdrantVectorStore store = QdrantVectorStore.builder()
					.baseUrl("http://127.0.0.1:" + port).collectionName(COLLECTION).dimension(4).build();
			assertThrows(AiException.class, store::size);
		}
	}
}
