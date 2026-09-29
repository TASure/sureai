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

import java.io.IOException;
import java.net.URI;
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
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.OpenSearchFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 OpenSearch REST API 的外部向量库实现（JDK {@link HttpClient} 直调，零新依赖）。
 *
 * <p>与 Elasticsearch 高度同源，但 kNN 语法与索引设置有差异（逐字核实，未直接复制 ES）：</p>
 * <ul>
 *   <li>批量写：{@code POST {base}/{index}/_bulk}（NDJSON）；</li>
 *   <li>检索：{@code POST {base}/{index}/_search}，
 *       {@code query.knn.<field>.{vector, k, filter}}（field 名为键、向量键为 {@code vector}）；</li>
 *   <li>删除：{@code POST {base}/{index}/_delete_by_query}（ids query）；</li>
 *   <li>计数：{@code GET {base}/{index}/_count}；清空：{@code DELETE {base}/{index}}；</li>
 *   <li>建索引：{@code PUT {base}/{index}}，settings 需 {@code index.knn=true}，
 *       mapping 为 {@code knn_vector} + dimension + method(hnsw/cosinesimil/lucene)。</li>
 * </ul>
 *
 * <p>鉴权：可选 apiKey（{@code Authorization: ApiKey <credential>}）。OpenSearch cosinesimil
 * 的 {@code _score} 为内部相关性得分（越大越相关），本实现原样作为结果得分透传，
 * 不做余弦区间还原。索引默认不自动创建。</p>
 *
 * <p>语法来源：<a href="https://docs.opensearch.org/2.15/search-plugins/knn/approximate-knn/">Approximate k-NN search</a>、
 * <a href="https://docs.opensearch.org/docs/2.5/search-plugins/knn/filter-search-knn/">k-NN search with filters</a>。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class OpenSearchVectorStore implements VectorStore {

	/** 默认 REST 基础地址。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:9200";

	private final String baseUrl;
	private final String apiKey;
	private final String indexName;
	private final String vectorField;
	private final String textField;
	private final int dimension;
	private final boolean autoCreateIndex;
	private final HttpClient httpClient;
	private final Duration timeout;
	private final OpenSearchFilterTranslator filterTranslator = new OpenSearchFilterTranslator();

	private OpenSearchVectorStore(Builder b) {
		this.baseUrl = trimSlash(Assert.notBlank(b.baseUrl, "baseUrl 不能为空"));
		this.apiKey = b.apiKey;
		this.indexName = Assert.notBlank(b.indexName, "indexName 不能为空");
		this.vectorField = Assert.notBlank(b.vectorField, "vectorField 不能为空");
		this.textField = Assert.notBlank(b.textField, "textField 不能为空");
		this.dimension = b.dimension;
		this.autoCreateIndex = b.autoCreateIndex;
		this.httpClient = b.httpClient == null ? HttpClient.newHttpClient() : b.httpClient;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		if (this.autoCreateIndex) {
			createIndex();
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
		StringBuilder ndjson = new StringBuilder();
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			JsonObject action = Json.object();
			JsonObject indexMeta = Json.object();
			indexMeta.set("_id", v.id());
			action.set("index", indexMeta);
			JsonObject source = Json.object();
			source.set(vectorField, toList(v.embedding()));
			source.set(textField, v.text() == null ? "" : v.text());
			for (Map.Entry<String, String> e : v.metadata().entrySet()) {
				source.set(e.getKey(), e.getValue());
			}
			ndjson.append(action).append('\n').append(source).append('\n');
		}
		postRaw(indexPath() + "/_bulk", ndjson.toString(), "application/x-ndjson");
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		JsonArray values = Json.array();
		values.set(id);
		JsonObject ids = Json.object();
		ids.set("values", values);
		JsonObject query = Json.object();
		query.set("ids", ids);
		JsonObject body = Json.object();
		body.set("query", query);
		post(indexPath() + "/_delete_by_query", body);
		return true;
	}

	@Override
	public void clear() {
		HttpRequest.Builder req = HttpRequest.newBuilder()
				.uri(URI.create(indexPath()))
				.timeout(timeout)
				.DELETE();
		applyAuth(req);
		try {
			httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("OpenSearch 删除索引失败: " + indexPath(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("OpenSearch 删除索引被中断: " + indexPath(), e);
		}
		if (autoCreateIndex) {
			createIndex();
		}
	}

	@Override
	public int size() {
		JsonObject resp = get(indexPath() + "/_count");
		return resp.optInt("count", -1);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY, null);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK, double minScore) {
		return similaritySearch(queryEmbedding, topK, minScore, null);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK, FilterExpression filter) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY, filter);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore, FilterExpression filter) {
		Assert.notNull(queryEmbedding, "queryEmbedding 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		JsonObject fieldClause = Json.object();
		fieldClause.set("vector", toList(queryEmbedding));
		fieldClause.set("k", topK);
		JsonObject translated = filterTranslator.translate(filter);
		if (translated != null) {
			fieldClause.set("filter", translated);
		}
		JsonObject knn = Json.object();
		knn.set(vectorField, fieldClause);
		JsonObject query = Json.object();
		query.set("knn", knn);
		JsonObject body = Json.object();
		body.set("size", topK);
		body.set("query", query);
		body.set("_source", true);
		JsonObject resp = post(indexPath() + "/_search", body);
		List<SimilaritySearchResult> results = new ArrayList<>();
		if (resp.has("hits") && resp.get("hits").isObject()) {
			JsonObject hits = resp.getJsonObject("hits");
			if (hits.has("hits") && hits.get("hits").isArray()) {
				JsonArray arr = hits.getJsonArray("hits");
				for (int i = 0; i < arr.size(); i++) {
					JsonObject hit = arr.getJsonObject(i);
					double score = hit.optDouble("_score", 0d);
					if (score < minScore) {
						continue;
					}
					String id = hit.optString("_id", "");
					JsonObject source = hit.has("_source") && hit.get("_source").isObject()
							? hit.getJsonObject("_source") : new JsonObject();
					String text = source.optString(textField, "");
					Map<String, String> metadata = toStringMap(source);
					float[] vector = source.has(vectorField) && source.get(vectorField).isArray()
							? toFloatArray(source.getJsonArray(vectorField)) : null;
					results.add(new SimilaritySearchResult(id, vector, text, metadata, score));
				}
			}
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * 创建索引（best-effort）：{@code index.knn=true} + knn_vector/cosinesimil 映射。
	 *
	 * @return 是否成功
	 */
	public boolean createIndex() {
		if (dimension <= 0) {
			throw new AiException("OpenSearch 创建索引需要 dimension，请通过 Builder.dimension 设置");
		}
		JsonObject indexSetting = Json.object();
		indexSetting.set("knn", true);
		JsonObject settings = Json.object();
		settings.set("index", indexSetting);
		JsonObject method = Json.object();
		method.set("name", "hnsw");
		method.set("space_type", "cosinesimil");
		method.set("engine", "lucene");
		JsonObject vectorProp = Json.object();
		vectorProp.set("type", "knn_vector");
		vectorProp.set("dimension", dimension);
		vectorProp.set("method", method);
		JsonObject props = Json.object();
		props.set(vectorField, vectorProp);
		JsonObject mappings = Json.object();
		mappings.set("properties", props);
		JsonObject body = Json.object();
		body.set("settings", settings);
		body.set("mappings", mappings);
		put(indexPath(), body, true);
		return true;
	}

	private String indexPath() {
		return baseUrl + "/" + indexName;
	}

	private static List<Double> toList(float[] vector) {
		List<Double> list = new ArrayList<>(vector.length);
		for (float f : vector) {
			list.add((double) f);
		}
		return list;
	}

	private static float[] toFloatArray(JsonArray arr) {
		float[] vector = new float[arr.size()];
		for (int i = 0; i < arr.size(); i++) {
			vector[i] = (float) arr.get(i).getAsDouble();
		}
		return vector;
	}

	private Map<String, String> toStringMap(JsonObject source) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String key : source.keySet()) {
			if (vectorField.equals(key) || textField.equals(key)) {
				continue;
			}
			if (source.get(key) != null && source.get(key).isString()) {
				map.put(key, source.get(key).getAsString());
			}
		}
		return map;
	}

	private JsonObject post(String path, JsonObject body) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
		applyAuth(builder);
		return parse(exchange(builder));
	}

	private JsonObject postRaw(String path, String body, String contentType) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.timeout(timeout)
				.header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		applyAuth(builder);
		return parse(exchange(builder));
	}

	private JsonObject put(String path, JsonObject body, boolean tolerateError) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
		applyAuth(builder);
		HttpResponse<String> resp = exchange(builder);
		if (tolerateError) {
			return new JsonObject();
		}
		return parse(resp);
	}

	private JsonObject get(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.timeout(timeout)
				.GET();
		applyAuth(builder);
		return parse(exchange(builder));
	}

	private JsonObject parse(HttpResponse<String> resp) {
		if (resp.statusCode() >= 400) {
			throw new AiException("OpenSearch REST 返回错误 status=" + resp.statusCode() + ", body=" + resp.body());
		}
		String text = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
		return (JsonObject) Json.parse(text);
	}

	private void applyAuth(HttpRequest.Builder builder) {
		if (apiKey != null && !apiKey.isBlank()) {
			builder.header("Authorization", "ApiKey " + apiKey);
		}
	}

	private HttpResponse<String> exchange(HttpRequest.Builder builder) {
		try {
			return httpClient.send(builder.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("OpenSearch REST 调用失败: " + builder.build().uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("OpenSearch REST 调用被中断: " + builder.build().uri(), e);
		}
	}

	private static String trimSlash(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * {@link OpenSearchVectorStore} 构建器。
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;
		private String apiKey;
		private String indexName;
		private String vectorField = "vector";
		private String textField = "text";
		private int dimension;
		private boolean autoCreateIndex;
		private HttpClient httpClient;
		private Duration timeout;

		private Builder() {
		}

		/**
		 * 设置基础 URL。
		 *
		 * @param baseUrl 如 http://localhost:9200
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
		 * 设置索引名（必需）。
		 *
		 * @param indexName 索引名
		 * @return this
		 */
		public Builder indexName(String indexName) {
			this.indexName = indexName;
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
		 * 设置向量维度（创建索引时必需，&gt;0）。
		 *
		 * @param dimension 维度
		 * @return this
		 */
		public Builder dimension(int dimension) {
			this.dimension = dimension;
			return this;
		}

		/**
		 * 是否构造时自动创建索引，默认 false。
		 *
		 * @param autoCreateIndex 是否自动创建
		 * @return this
		 */
		public Builder autoCreateIndex(boolean autoCreateIndex) {
			this.autoCreateIndex = autoCreateIndex;
			return this;
		}

		/**
		 * 注入自定义 {@link HttpClient}（便于测试替换为本地 mock）。
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
		 * @return OpenSearchVectorStore
		 */
		public OpenSearchVectorStore build() {
			return new OpenSearchVectorStore(this);
		}
	}
}
