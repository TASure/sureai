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

package com.sure.ai.rag.store.typesense;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
import com.sure.ai.rag.store.filter.TypesenseFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 Typesense REST API 的外部向量库实现（JDK {@link HttpClient}）。
 *
 * <p>仅依赖 JDK {@link HttpClient}，不引入 Typesense 官方客户端。端点约定：</p>
 * <ul>
 *   <li>建集合：{@code POST /collections}（body: {@code name, fields:[{name,type,num_dim}]}）；</li>
 *   <li>写入：{@code POST /collections/{coll}/documents?action=upsert}（body 为文档 JSON 数组）；</li>
 *   <li>检索：{@code GET /collections/{coll}/documents/search?q=*&vector_query=vec:([...],k:n)
 *       &filter_by=...}；</li>
 *   <li>删除：{@code DELETE /collections/{coll}/documents/{id}}；</li>
 *   <li>计数：{@code GET /collections/{coll}}（响应 {@code num_doc}）。</li>
 * </ul>
 *
 * <p>鉴权：API Key 通过 {@code X-TYPESENSE-API-KEY} 请求头传递。
 * 向量搜索用 {@code vector_query=vec:([f1,f2,...], k:n)} 语法（{@code q=*} 通配），
 * 响应 {@code hits[].vector_distance} 为余弦距离（0 最相似），还原为
 * {@code cosine = 1 - vector_distance}。集合默认不自动创建。</p>
 *
 * <p>元数据过滤：{@link FilterExpression} 经 {@link TypesenseFilterTranslator}
 * 翻译为 {@code filter_by} 表达式（有限子集）。</p>
 *
 * <p>语法来源：
 * <a href="https://typesense.org/docs/0.24.0/api/vector-search.html">Vector Search</a>、
 * <a href="https://typesense.org/docs/30.1/api/search.html">Search API</a>、
 * <a href="https://typesense.org/docs/30.0/api/collections.html">Collections</a>。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class TypesenseVectorStore implements VectorStore {

	/** 默认 REST 基础地址。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:8108";

	private final String baseUrl;
	private final String apiKey;
	private final String collectionName;
	private final String vectorField;
	private final String textField;
	private final int dimension;
	private final boolean autoCreateCollection;
	private final HttpClient httpClient;
	private final Duration timeout;
	private final TypesenseFilterTranslator filterTranslator = new TypesenseFilterTranslator();

	private TypesenseVectorStore(Builder b) {
		this.baseUrl = trimSlash(Assert.notBlank(b.baseUrl, "baseUrl 不能为空"));
		this.apiKey = b.apiKey;
		this.collectionName = Assert.notBlank(b.collectionName, "collectionName 不能为空");
		this.vectorField = Assert.notBlank(b.vectorField, "vectorField 不能为空");
		this.textField = Assert.notBlank(b.textField, "textField 不能为空");
		this.dimension = b.dimension;
		this.autoCreateCollection = b.autoCreateCollection;
		this.httpClient = b.httpClient == null ? HttpClient.newHttpClient() : b.httpClient;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		if (this.autoCreateCollection) {
			createCollection();
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
		JsonArray docs = Json.array();
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			JsonObject doc = Json.object();
			doc.set("id", v.id());
			doc.set(textField, v.text() == null ? "" : v.text());
			doc.set(vectorField, toList(v.embedding()));
			for (Map.Entry<String, String> e : v.metadata().entrySet()) {
				doc.set(e.getKey(), e.getValue());
			}
			docs.set(doc);
		}
		post("/collections/" + collectionName + "/documents?action=upsert", (Object) docs, false);
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		JsonObject resp = exchangeJson(HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/collections/" + collectionName + "/documents/" + enc(id)))
				.timeout(timeout)
				.DELETE());
		return resp.optInt("num_deleted", 0) > 0;
	}

	@Override
	public void clear() {
		deleteCollection();
		if (autoCreateCollection) {
			createCollection();
		}
	}

	@Override
	public int size() {
		JsonObject resp = get("/collections/" + collectionName);
		return resp.optInt("num_doc", -1);
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
		StringBuilder vecParam = new StringBuilder(vectorField).append(":([");
		for (int i = 0; i < queryEmbedding.length; i++) {
			if (i > 0) {
				vecParam.append(',');
			}
			vecParam.append(queryEmbedding[i]);
		}
		vecParam.append("], k:").append(topK).append(')');
		StringBuilder url = new StringBuilder("/collections/").append(collectionName)
				.append("/documents/search?q=*")
				.append("&vector_query=").append(enc(vecParam.toString()));
		String filterBy = filterTranslator.translate(filter);
		if (filterBy != null && !filterBy.isBlank()) {
			url.append("&filter_by=").append(enc(filterBy));
		}
		JsonObject resp = get(url.toString());
		JsonArray hits = resp.has("hits") ? resp.getJsonArray("hits") : new JsonArray();
		List<SimilaritySearchResult> results = new ArrayList<>(hits.size());
		for (int i = 0; i < hits.size(); i++) {
			JsonObject hit = hits.getJsonObject(i);
			double distance = hit.optDouble("vector_distance", 0d);
			double score = 1d - distance;
			if (score < minScore) {
				continue;
			}
			JsonObject doc = hit.has("document") && hit.get("document").isObject()
					? hit.getJsonObject("document") : new JsonObject();
			String id = doc.optString("id", "");
			String text = doc.optString(textField, "");
			Map<String, String> metadata = toStringMap(doc);
			results.add(new SimilaritySearchResult(id, null, text, metadata, score));
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * 创建集合（best-effort）：已存在时 Typesense 返回业务错误，此处忽略不抛。
	 *
	 * @return 是否成功
	 */
	public boolean createCollection() {
		if (dimension <= 0) {
			throw new AiException("Typesense 创建集合需要 dimension，请通过 Builder.dimension 设置");
		}
		JsonObject body = Json.object();
		body.set("name", collectionName);
		JsonArray fields = Json.array();
		JsonObject idField = Json.object();
		idField.set("name", "id");
		idField.set("type", "string");
		fields.set(idField);
		JsonObject textFieldObj = Json.object();
		textFieldObj.set("name", textField);
		textFieldObj.set("type", "string");
		fields.set(textFieldObj);
		JsonObject vecField = Json.object();
		vecField.set("name", vectorField);
		vecField.set("type", "float[]");
		vecField.set("num_dim", dimension);
		fields.set(vecField);
		body.set("fields", fields);
		post("/collections", body, true);
		return true;
	}

	private void deleteCollection() {
		HttpRequest.Builder req = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/collections/" + collectionName))
				.timeout(timeout)
				.DELETE();
		applyAuth(req);
		exchange(req.build());
	}

	private static List<Double> toList(float[] vector) {
		List<Double> list = new ArrayList<>(vector.length);
		for (float f : vector) {
			list.add((double) f);
		}
		return list;
	}

	private Map<String, String> toStringMap(JsonObject doc) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String key : doc.keySet()) {
			if ("id".equals(key) || textField.equals(key) || vectorField.equals(key)) {
				continue;
			}
			if (doc.get(key) != null && doc.get(key).isString()) {
				map.put(key, doc.get(key).getAsString());
			}
		}
		return map;
	}

	private JsonObject get(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + path))
				.timeout(timeout)
				.GET();
		applyAuth(builder);
		return parse(exchange(builder.build()));
	}

	private JsonObject post(String path, Object body, boolean tolerateError) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + path))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(String.valueOf(body), StandardCharsets.UTF_8));
		applyAuth(builder);
		HttpResponse<String> resp = exchange(builder.build());
		if (tolerateError) {
			return new JsonObject();
		}
		return parse(resp);
	}

	private JsonObject exchangeJson(HttpRequest.Builder builder) {
		applyAuth(builder);
		return parse(exchange(builder.build()));
	}

	private JsonObject parse(HttpResponse<String> resp) {
		if (resp.statusCode() >= 400) {
			throw new AiException("Typesense REST 返回错误 status=" + resp.statusCode()
					+ ", body=" + resp.body());
		}
		String text = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
		return (JsonObject) Json.parse(text);
	}

	private void applyAuth(HttpRequest.Builder builder) {
		if (apiKey != null && !apiKey.isBlank()) {
			builder.header("X-TYPESENSE-API-KEY", apiKey);
		}
	}

	private HttpResponse<String> exchange(HttpRequest request) {
		try {
			return httpClient.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("Typesense REST 调用失败: " + request.uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Typesense REST 调用被中断: " + request.uri(), e);
		}
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String trimSlash(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * {@link TypesenseVectorStore} 构建器。
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;
		private String apiKey;
		private String collectionName;
		private String vectorField = "vector";
		private String textField = "text";
		private int dimension;
		private boolean autoCreateCollection;
		private HttpClient httpClient;
		private Duration timeout;

		private Builder() {
		}

		/**
		 * 设置基础 URL。
		 *
		 * @param baseUrl 如 http://localhost:8108
		 * @return this
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * 设置 API Key，为空则不发送鉴权头。
		 *
		 * @param apiKey API Key
		 * @return this
		 */
		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		/**
		 * 设置集合名（必需）。
		 *
		 * @param collectionName 集合名
		 * @return this
		 */
		public Builder collectionName(String collectionName) {
			this.collectionName = collectionName;
			return this;
		}

		/**
		 * 设置向量字段名，默认 vector。
		 *
		 * @param vectorField 向量字段名
		 * @return this
		 */
		public Builder vectorField(String vectorField) {
			this.vectorField = vectorField;
			return this;
		}

		/**
		 * 设置文本字段名，默认 text。
		 *
		 * @param textField 文本字段名
		 * @return this
		 */
		public Builder textField(String textField) {
			this.textField = textField;
			return this;
		}

		/**
		 * 设置向量维度（创建集合时必需，&gt;0）。
		 *
		 * @param dimension 维度
		 * @return this
		 */
		public Builder dimension(int dimension) {
			this.dimension = dimension;
			return this;
		}

		/**
		 * 是否构造时自动创建集合，默认 false。
		 *
		 * @param autoCreateCollection 是否自动创建
		 * @return this
		 */
		public Builder autoCreateCollection(boolean autoCreateCollection) {
			this.autoCreateCollection = autoCreateCollection;
			return this;
		}

		/**
		 * 注入自定义 {@link HttpClient}（便于测试替换为本地 mock 地址）。
		 *
		 * @param httpClient HTTP 客户端
		 * @return this
		 */
		public Builder httpClient(HttpClient httpClient) {
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
		 * @return TypesenseVectorStore
		 */
		public TypesenseVectorStore build() {
			return new TypesenseVectorStore(this);
		}
	}
}
