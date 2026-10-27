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
package com.sure.ai.rag.store.neo4j;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.VectorStore;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.Neo4jFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 Neo4j 向量索引的外部向量库实现（JDK {@link java.net.http.HttpClient} 调用官方 HTTP 事务性 API）。
 *
 * <p><b>协议路径（如实标注）</b>：Neo4j 原生二进制协议为 Bolt（packstream 编解码 + 版本握手 + 分块帧），
 * 其可变长度整数/长度前缀与分块握手的手写工作量与出错风险显著高于本批其余两项。本实现改用 Neo4j
 * <b>官方且长期支持的 HTTP 事务性端点</b> {@code POST /db/{db}/tx/commit}（JSON over HTTP），
 * 与 {@code TypesenseVectorStore} 同型，零第三方依赖。Bolt/packstream 作为后续路径评估，不在本批实现。</p>
 *
 * <p>端点约定：请求体 {@code {"statements":[{"statement":Cypher,"parameters":{...}}]}}；
 * 响应 {@code {"results":[{"columns":[...],"data":[{"row":[...]}]}],"errors":[...]}}；
 * 鉴权为 HTTP Basic（{@code Authorization: Basic base64(user:password)}）。</p>
 *
 * <p>向量检索：{@code CALL db.index.vector.queryNodes($index,$k,$embedding) YIELD node, score}，
 * 随后 {@code RETURN node.id/text/metadata, score}；得分即余弦相似度（由向量索引 cosine 配置决定）。
 * 过滤：{@link FilterExpression} 经 {@link Neo4jFilterTranslator} 翻译为 {@code WHERE n.prop ...}（后过滤）。</p>
 *
 * <p>语法来源：
 * <a href="https://neo4j.com/docs/http-api/current/query/">HTTP API: Query the database</a>、
 * <a href="https://neo4j.com/docs/cypher-manual/current/indexes/semantic-indexes/vector-indexes/">Vector indexes</a>。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class Neo4jVectorStore implements VectorStore {

	/** 默认基础地址。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:7474";

	private final String baseUrl;
	private final String database;
	private final String user;
	private final String password;
	private final String vectorIndex;
	private final String label;
	private final int dimension;
	private final boolean autoCreateIndex;
	private final java.net.http.HttpClient httpClient;
	private final Duration timeout;
	private final Neo4jFilterTranslator filterTranslator = new Neo4jFilterTranslator();

	private Neo4jVectorStore(Builder b) {
		this.baseUrl = trimSlash(Assert.notBlank(b.baseUrl, "baseUrl 不能为空"));
		this.database = Assert.notBlank(b.database, "database 不能为空");
		this.user = b.user;
		this.password = b.password;
		this.vectorIndex = Assert.notBlank(b.vectorIndex, "vectorIndex 不能为空");
		this.label = Assert.notBlank(b.label, "label 不能为空");
		this.dimension = b.dimension;
		this.autoCreateIndex = b.autoCreateIndex;
		this.httpClient = b.httpClient == null ? java.net.http.HttpClient.newHttpClient() : b.httpClient;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		if (this.autoCreateIndex) {
			createVectorIndex();
		}
	}

	/**
	 * 创建构建器。
	 *
	 * @return 构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public void add(Vector vector) {
		Assert.notNull(vector, "vector 不能为 null");
		addAll(List.of(vector));
	}

	@Override
	public void addAll(List<Vector> batch) {
		Assert.notNull(batch, "batch 不能为 null");
		if (batch.isEmpty()) {
			return;
		}
		JsonArray statements = Json.array();
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			JsonObject params = Json.object();
			params.set("id", v.id());
			params.set("text", v.text() == null ? "" : v.text());
			params.set("metadata", metadataObject(v.metadata()));
			params.set("embedding", doubleArray(v.embedding()));
			JsonObject stmt = Json.object();
			stmt.set("statement", "MERGE (n:" + label + " {id:$id}) SET n.text=$text,"
					+ " n.metadata=$metadata, n.embedding=$embedding");
			stmt.set("parameters", params);
			statements.set(stmt);
		}
		commit(statements);
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		JsonObject params = Json.object();
		params.set("id", id);
		JsonObject stmt = Json.object();
		stmt.set("statement", "MATCH (n:" + label + " {id:$id}) DETACH DELETE n");
		stmt.set("parameters", params);
		JsonArray statements = Json.array();
		statements.set(stmt);
		commit(statements);
		return true;
	}

	@Override
	public void clear() {
		JsonObject stmt = Json.object();
		stmt.set("statement", "MATCH (n:" + label + ") DETACH DELETE n");
		JsonArray statements = Json.array();
		statements.set(stmt);
		commit(statements);
	}

	@Override
	public int size() {
		JsonObject stmt = Json.object();
		stmt.set("statement", "MATCH (n:" + label + ") RETURN count(n) AS c");
		JsonArray statements = Json.array();
		statements.set(stmt);
		JsonObject resp = commit(statements);
		List<Object> rows = firstRows(resp);
		if (!rows.isEmpty() && rows.get(0) instanceof List<?> row && !row.isEmpty()) {
			Object c = row.get(0);
			return c instanceof Number n ? n.intValue() : -1;
		}
		return -1;
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY, null);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore) {
		return similaritySearch(queryEmbedding, topK, minScore, null);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			FilterExpression filter) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY, filter);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore, FilterExpression filter) {
		Assert.notNull(queryEmbedding, "queryEmbedding 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		StringBuilder cql = new StringBuilder("CALL db.index.vector.queryNodes($index,$k,$embedding)")
				.append(" YIELD node, score");
		String where = filterTranslator.translate(filter);
		if (where != null && !where.isBlank()) {
			cql.append(" WHERE ").append(where);
		}
		cql.append(" RETURN node.id AS id, node.text AS text, node.metadata AS metadata, score AS score");
		JsonObject params = Json.object();
		params.set("index", vectorIndex);
		params.set("k", topK);
		params.set("embedding", doubleArray(queryEmbedding));
		JsonObject stmt = Json.object();
		stmt.set("statement", cql.toString());
		stmt.set("parameters", params);
		JsonArray statements = Json.array();
		statements.set(stmt);
		JsonObject resp = commit(statements);
		List<Object> rows = firstRows(resp);
		List<SimilaritySearchResult> results = new ArrayList<>(rows.size());
		for (Object rowObj : rows) {
			if (!(rowObj instanceof List<?> row) || row.size() < 4) {
				continue;
			}
			double score = row.get(3) instanceof Number n ? n.doubleValue() : 0d;
			if (score < minScore) {
				continue;
			}
			String id = String.valueOf(row.get(0));
			String text = row.get(1) == null ? "" : String.valueOf(row.get(1));
			Map<String, String> metadata = new LinkedHashMap<>();
			if (row.get(2) instanceof Map<?, ?> meta) {
				for (Map.Entry<?, ?> e : meta.entrySet()) {
					metadata.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
				}
			}
			results.add(new SimilaritySearchResult(id, null, text, metadata, score));
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * best-effort 创建向量索引：已存在/不支持时忽略错误。
	 *
	 * @return 是否发送
	 */
	public boolean createVectorIndex() {
		if (dimension <= 0) {
			throw new AiException("Neo4j 创建向量索引需要 dimension，请通过 Builder.dimension 设置");
		}
		String cql = "CREATE VECTOR INDEX " + neoIdent(vectorIndex) + " FOR (n:" + label + ") ON (n.embedding)"
				+ " OPTIONS {indexConfig: {`vector.dimensions`: " + dimension
				+ ", `vector.similarity_function`: 'cosine'}}";
		try {
			JsonObject stmt = Json.object();
			stmt.set("statement", cql);
			JsonArray statements = Json.array();
			statements.set(stmt);
			commit(statements);
		} catch (AiException ignored) {
			// 索引已存在或版本不支持：忽略，需离线创建。
		}
		return true;
	}

	// ===== HTTP 事务提交 =====

	private JsonObject commit(JsonArray statements) {
		JsonObject body = Json.object();
		body.set("statements", statements);
		java.net.http.HttpRequest.Builder req = java.net.http.HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/db/" + database + "/tx/commit"))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(java.net.http.HttpRequest.BodyPublishers
						.ofString(String.valueOf(body), StandardCharsets.UTF_8));
		if (user != null && !user.isBlank()) {
			String token = Base64.getEncoder()
					.encodeToString((user + ":" + (password == null ? "" : password))
							.getBytes(StandardCharsets.UTF_8));
			req.header("Authorization", "Basic " + token);
		}
		java.net.http.HttpResponse<String> httpResp;
		try {
			httpResp = httpClient.send(req.build(),
					java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("Neo4j HTTP 调用失败: " + baseUrl, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Neo4j HTTP 调用被中断: " + baseUrl, e);
		}
		if (httpResp.statusCode() >= 400) {
			throw new AiException("Neo4j HTTP 返回 status=" + httpResp.statusCode()
					+ ", body=" + httpResp.body());
		}
		JsonObject resp = (JsonObject) Json.parse(httpResp.body() == null || httpResp.body().isBlank()
				? "{}" : httpResp.body());
		if (resp.has("errors") && resp.get("errors").isArray()) {
			JsonArray errors = resp.getJsonArray("errors");
			if (errors.size() > 0) {
				JsonObject first = errors.getJsonObject(0);
				throw new AiException("Neo4j 语句错误: " + first.optString("code", "")
						+ " " + first.optString("message", ""));
			}
		}
		return resp;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> firstRows(JsonObject resp) {
		List<Object> out = new ArrayList<>();
		if (!resp.has("results") || !resp.get("results").isArray()) {
			return out;
		}
		JsonArray results = resp.getJsonArray("results");
		if (results.size() == 0) {
			return out;
		}
		JsonObject result = results.getJsonObject(0);
		if (!result.has("data") || !result.get("data").isArray()) {
			return out;
		}
		JsonArray data = result.getJsonArray("data");
		for (int i = 0; i < data.size(); i++) {
			JsonObject rowObj = data.getJsonObject(i);
			if (rowObj.has("row") && rowObj.get("row").isArray()) {
				JsonArray row = rowObj.getJsonArray("row");
				List<Object> cells = new ArrayList<>(row.size());
				for (int j = 0; j < row.size(); j++) {
					cells.add(toJava(row.get(j)));
				}
				out.add(cells);
			}
		}
		return out;
	}

	private static Object toJava(com.sure.ai.internal.json.JsonElement el) {
		if (el == null || el.isNull()) {
			return null;
		}
		if (el.isNumber()) {
			return el.getAsDouble();
		}
		if (el.isBoolean()) {
			return el.getAsBoolean();
		}
		if (el.isString()) {
			return el.getAsString();
		}
		if (el.isObject()) {
			JsonObject o = (JsonObject) el;
			Map<String, Object> map = new LinkedHashMap<>();
			for (String k : o.keySet()) {
				map.put(k, toJava(o.get(k)));
			}
			return map;
		}
		if (el.isArray()) {
			JsonArray arr = (JsonArray) el;
			List<Object> list = new ArrayList<>(arr.size());
			for (int i = 0; i < arr.size(); i++) {
				list.add(toJava(arr.get(i)));
			}
			return list;
		}
		return null;
	}

	private static JsonObject metadataObject(Map<String, String> metadata) {
		JsonObject o = Json.object();
		for (Map.Entry<String, String> e : metadata.entrySet()) {
			o.set(e.getKey(), e.getValue());
		}
		return o;
	}

	private static JsonArray doubleArray(float[] v) {
		JsonArray arr = Json.array();
		for (float f : v) {
			arr.set((double) f);
		}
		return arr;
	}

	private static String neoIdent(String name) {
		return "`" + name.replace("`", "``") + "`";
	}

	private static String trimSlash(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * {@link Neo4jVectorStore} 构建器。
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;
		private String database = "neo4j";
		private String user;
		private String password;
		private String vectorIndex;
		private String label = "Doc";
		private int dimension;
		private boolean autoCreateIndex;
		private java.net.http.HttpClient httpClient;
		private Duration timeout;

		private Builder() {
		}

		/**
		 * 设置基础 URL。
		 *
		 * @param baseUrl 如 http://localhost:7474
		 * @return this
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * 设置数据库名，默认 neo4j。
		 *
		 * @param database 数据库名
		 * @return this
		 */
		public Builder database(String database) {
			this.database = database;
			return this;
		}

		/**
		 * 设置用户名（HTTP Basic 认证）。
		 *
		 * @param user 用户名
		 * @return this
		 */
		public Builder user(String user) {
			this.user = user;
			return this;
		}

		/**
		 * 设置密码。
		 *
		 * @param password 密码
		 * @return this
		 */
		public Builder password(String password) {
			this.password = password;
			return this;
		}

		/**
		 * 设置向量索引名（必需）。
		 *
		 * @param vectorIndex 向量索引名
		 * @return this
		 */
		public Builder vectorIndex(String vectorIndex) {
			this.vectorIndex = vectorIndex;
			return this;
		}

		/**
		 * 设置节点标签，默认 Doc。
		 *
		 * @param label 节点标签
		 * @return this
		 */
		public Builder label(String label) {
			this.label = label;
			return this;
		}

		/**
		 * 设置向量维度（自动建索引时必需，&gt;0）。
		 *
		 * @param dimension 维度
		 * @return this
		 */
		public Builder dimension(int dimension) {
			this.dimension = dimension;
			return this;
		}

		/**
		 * 是否构造时 best-effort 自动创建向量索引，默认 false。
		 *
		 * @param autoCreateIndex 是否自动创建
		 * @return this
		 */
		public Builder autoCreateIndex(boolean autoCreateIndex) {
			this.autoCreateIndex = autoCreateIndex;
			return this;
		}

		/**
		 * 注入自定义 {@link java.net.http.HttpClient}（便于测试替换为本地 mock 地址）。
		 *
		 * @param httpClient HTTP 客户端
		 * @return this
		 */
		public Builder httpClient(java.net.http.HttpClient httpClient) {
			this.httpClient = httpClient;
			return this;
		}

		/**
		 * 设置请求超时，默认 10 秒。
		 *
		 * @param timeout 超时
		 * @return this
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * 构建实例。
		 *
		 * @return Neo4jVectorStore
		 */
		public Neo4jVectorStore build() {
			return new Neo4jVectorStore(this);
		}
	}
}
