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
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.neo4j.Neo4jVectorStore;

/**
 * {@link Neo4jVectorStore} 单元测试：本地 {@link HttpServer} mock HTTP 事务端点，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class Neo4jVectorStoreTest {

	private static final String INDEX = "vec_idx";

	private HttpServer server;
	private String baseUrl;

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	private Neo4jVectorStore.Builder baseBuilder() {
		return Neo4jVectorStore.builder().baseUrl(baseUrl).vectorIndex(INDEX);
	}

	private static void respond(HttpExchange ex, String body) throws IOException {
		byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static String readBody(HttpExchange ex) throws IOException {
		return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
	}

	private static Vector sampleVector(String id) {
		return Vector.of(id, new float[] { 0.1f, 0.2f, 0.3f }, "text-" + id,
				Map.of("source", id + ".pdf"));
	}

	/** addAll：MERGE 语句 + 参数含 id/text/metadata/embedding。 */
	@Test
	public void testAddAll() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		AtomicReference<String> authRef = new AtomicReference<>();
		this.server.createContext("/db/neo4j/tx/commit", ex -> {
			authRef.set(ex.getRequestHeaders().getFirst("Authorization"));
			bodyRef.set(readBody(ex));
			respond(ex, "{\"results\":[],\"errors\":[]}");
		});
		baseBuilder().user("neo4j").password("secret").build().addAll(List.of(sampleVector("v1")));
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		JsonArray stmts = body.getJsonArray("statements");
		JsonObject s0 = stmts.getJsonObject(0);
		assertTrue(s0.getString("statement").startsWith("MERGE"));
		JsonObject params = s0.getJsonObject("parameters");
		assertEquals("v1", params.getString("id"));
		assertEquals(3, params.getJsonArray("embedding").size());
		assertTrue(authRef.get().startsWith("Basic "));
	}

	/** 检索：db.index.vector.queryNodes + 返回 row 还原 score。 */
	@Test
	public void testSearch() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/db/neo4j/tx/commit", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, "{\"results\":[{\"columns\":[\"id\",\"text\",\"metadata\",\"score\"],"
					+ "\"data\":["
					+ "{\"row\":[\"v1\",\"hello\",{\"source\":\"a.pdf\"},0.8]},"
					+ "{\"row\":[\"v2\",\"world\",{},0.9]}"
					+ "]}],\"errors\":[]}");
		});
		List<SimilaritySearchResult> results = baseBuilder().build()
				.similaritySearch(new float[] { 0.1f, 0.2f, 0.3f }, 2);
		JsonObject body = (JsonObject) Json.parse(bodyRef.get());
		String cql = body.getJsonArray("statements").getJsonObject(0).getString("statement");
		assertTrue(cql, cql.contains("db.index.vector.queryNodes"));
		assertEquals(2, results.size());
		// v2 score 0.9 在前
		assertEquals("v2", results.get(0).id());
		assertEquals(0.9, results.get(0).score(), 1e-9);
		assertEquals("v1", results.get(1).id());
		assertEquals(0.8, results.get(1).score(), 1e-9);
		assertEquals("a.pdf", results.get(1).metadata().get("source"));
	}

	/** 带 filter：Cypher 含 WHERE n.tenant。 */
	@Test
	public void testSearchWithFilter() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/db/neo4j/tx/commit", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, "{\"results\":[{\"columns\":[\"id\"],\"data\":[]}],\"errors\":[]}");
		});
		baseBuilder().build().similaritySearch(new float[] { 0.1f, 0.2f }, 1,
				FilterExpression.eq("tenant", "acme"));
		String cql = ((JsonObject) Json.parse(bodyRef.get())).getJsonArray("statements")
				.getJsonObject(0).getString("statement");
		assertTrue(cql, cql.contains("WHERE n.tenant = 'acme'"));
	}

	/** size：count(n) 返回首格。 */
	@Test
	public void testSize() {
		this.server.createContext("/db/neo4j/tx/commit",
				ex -> respond(ex, "{\"results\":[{\"columns\":[\"c\"],\"data\":[{\"row\":[7]}]}],\"errors\":[]}"));
		assertEquals(7, baseBuilder().build().size());
	}

	/** delete：DETACH DELETE 语句。 */
	@Test
	public void testDelete() {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		this.server.createContext("/db/neo4j/tx/commit", ex -> {
			bodyRef.set(readBody(ex));
			respond(ex, "{\"results\":[],\"errors\":[]}");
		});
		baseBuilder().build().delete("v1");
		String cql = ((JsonObject) Json.parse(bodyRef.get())).getJsonArray("statements")
				.getJsonObject(0).getString("statement");
		assertTrue(cql, cql.contains("DETACH DELETE"));
	}

	/** 服务端 errors 非空抛 AiException。 */
	@Test
	public void testErrorsThrows() {
		this.server.createContext("/db/neo4j/tx/commit", ex -> respond(ex,
				"{\"results\":[],\"errors\":[{\"code\":\"Neo.ClientError.Statement\",\"message\":\"Index not found\"}]}"));
		assertThrows(AiException.class,
				() -> baseBuilder().build().similaritySearch(new float[] { 0.1f }, 1));
	}
}
