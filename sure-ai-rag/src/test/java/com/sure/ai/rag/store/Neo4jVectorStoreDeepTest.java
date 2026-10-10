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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.neo4j.Neo4jVectorStore;

/**
 * {@link Neo4jVectorStore} 错误/边界分支单元测试：本地 {@link HttpServer} mock 覆盖
 * 空批量/null 元素、clear、size 解析、minScore 过滤、畸形行、自动建索引吞错、
 * HTTP 4xx/5xx、空响应体、空 errors、firstRows 各缺失分支，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class Neo4jVectorStoreDeepTest {

	private HttpServer server;
	private String baseUrl;
	private volatile int status = 200;
	private volatile String body = "{\"results\":[],\"errors\":[]}";

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/db/neo4j/tx/commit", ex -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
			try (OutputStream os = ex.getResponseBody()) {
				if (bytes.length > 0) {
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

	private Neo4jVectorStore.Builder baseBuilder() {
		return Neo4jVectorStore.builder().baseUrl(baseUrl + "/").vectorIndex("vec_idx");
	}

	private static Vector v(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f }, "text-" + id, Map.of("source", id + ".pdf"));
	}

	/** 空批量直接返回，不发请求。 */
	@Test
	public void testAddAllEmpty() {
		baseBuilder().build().addAll(List.of());
	}

	/** add(Vector) 委托。 */
	@Test
	public void testAddSingle() {
		baseBuilder().build().add(v("v1"));
	}

	/** 构建器全量 setter。 */
	@Test
	public void testBuilderSetters() {
		baseBuilder().database("neo4j").label("Doc").timeout(java.time.Duration.ofSeconds(2)).build();
	}

	/** 批量含 null 元素：跳过 null，正常提交。 */
	@Test
	public void testAddAllWithNull() {
		AtomicReference<String> ref = new AtomicReference<>();
		this.server.removeContext("/db/neo4j/tx/commit");
		this.server.createContext("/db/neo4j/tx/commit", ex -> {
			ref.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(ex, "{\"results\":[],\"errors\":[]}");
		});
		baseBuilder().build().addAll(new java.util.ArrayList<>(java.util.Arrays.asList(v("v1"), null, v("v2"))));
		JsonArray stmts = ((JsonObject) Json.parse(ref.get())).getJsonArray("statements");
		assertEquals(2, stmts.size());
	}

	/** clear：发送 DETACH DELETE 全量语句。 */
	@Test
	public void testClear() {
		AtomicReference<String> ref = new AtomicReference<>();
		this.server.removeContext("/db/neo4j/tx/commit");
		this.server.createContext("/db/neo4j/tx/commit", ex -> {
			ref.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(ex, "{\"results\":[],\"errors\":[]}");
		});
		baseBuilder().build().clear();
		assertTrue(((JsonObject) Json.parse(ref.get())).getJsonArray("statements")
				.getJsonObject(0).getString("statement").contains("DETACH DELETE"));
	}

	/** size：count 非数字 → -1；无行 → -1。 */
	@Test
	public void testSizeEdge() {
		this.body = "{\"results\":[{\"columns\":[\"c\"],\"data\":[{\"row\":[\"abc\"]}]}],\"errors\":[]}";
		assertEquals(-1, baseBuilder().build().size());
		this.body = "{\"results\":[],\"errors\":[]}";
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 检索：minScore 过滤低分行；畸形行（非 List/列数不足）跳过；score 非数字按 0。 */
	@Test
	public void testSearchRowsEdge() {
		this.body = "{\"results\":[{\"columns\":[\"id\",\"text\",\"metadata\",\"score\"],\"data\":["
				+ "{\"row\":[\"low\",\"t\",{},0.1]},"
				+ "{\"row\":[\"short\",1,{\"x\":1}]},"
				+ "{\"row\":[\"high\",\"keep\",{\"source\":\"a.pdf\"},0.9]}"
				+ "]}],\"errors\":[]}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5, null);
		assertEquals(1, r.size());
		assertEquals("high", r.get(0).id());
		assertEquals("a.pdf", r.get(0).metadata().get("source"));
	}

	/** 检索：text 为 null 置空串；metadata 非 Map 忽略。 */
	@Test
	public void testSearchNullTextAndMeta() {
		this.body = "{\"results\":[{\"columns\":[\"id\",\"text\",\"metadata\",\"score\"],\"data\":["
				+ "{\"row\":[\"v1\",null,null,0.8]}"
				+ "]}],\"errors\":[]}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 1);
		assertEquals(1, r.size());
		assertEquals("", r.get(0).text());
		assertTrue(r.get(0).metadata().isEmpty());
	}

	/** 检索：metadata 含布尔与数组值，toJava 多类型分支。 */
	@Test
	public void testSearchMetaBooleanAndArray() {
		this.body = "{\"results\":[{\"columns\":[\"id\",\"text\",\"metadata\",\"score\"],\"data\":["
				+ "{\"row\":[\"v1\",\"t\",{\"flag\":true,\"tags\":[\"a\",\"b\"]},0.9]}"
				+ "]}],\"errors\":[]}";
		List<SimilaritySearchResult> r = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f }, 1, 0.5);
		assertEquals(1, r.size());
		assertEquals("true", r.get(0).metadata().get("flag"));
	}

	/** 自动建索引：dimension>0 时 best-effort，服务端报错也吞掉。 */
	@Test
	public void testAutoCreateIndexSwallowsError() {
		this.status = 200;
		this.body = "{\"results\":[],\"errors\":[{\"code\":\"X\",\"message\":\"exists\"}]}";
		// 构造期 createVectorIndex 捕获 AiException，不抛
		baseBuilder().dimension(3).autoCreateIndex(true).build();
	}

	/** 自动建索引缺 dimension → AiException。 */
	@Test
	public void testAutoCreateNoDimensionThrows() {
		assertThrows(AiException.class, () -> baseBuilder().autoCreateIndex(true).build());
	}

	/** HTTP 5xx → AiException。 */
	@Test
	public void testHttpErrorThrows() {
		this.status = 500;
		this.body = "boom";
		assertThrows(AiException.class, () -> baseBuilder().build().size());
	}

	/** 空响应体按 {} 解析，无 results → size -1。 */
	@Test
	public void testBlankBody() {
		this.status = 200;
		this.body = "";
		assertEquals(-1, baseBuilder().build().size());
	}

	/** errors 为空数组 → 不抛。 */
	@Test
	public void testEmptyErrorsNoThrow() {
		this.body = "{\"results\":[],\"errors\":[]}";
		assertEquals(-1, baseBuilder().build().size());
	}

	/** 连接拒绝 → AiException。 */
	@Test
	public void testConnectionRefusedThrows() {
		int port = server.getAddress().getPort();
		server.stop(0);
		Neo4jVectorStore store = Neo4jVectorStore.builder()
				.baseUrl("http://127.0.0.1:" + port).vectorIndex("idx").build();
		assertThrows(AiException.class, () -> store.size());
	}

	private static void respond(HttpExchange ex, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}
}
