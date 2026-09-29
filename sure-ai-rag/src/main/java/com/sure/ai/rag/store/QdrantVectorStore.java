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
import com.sure.ai.rag.store.filter.QdrantFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 Qdrant REST API 的外部向量库实现。
 *
 * <p>仅依赖 JDK {@link HttpClient}，不引入 Qdrant 官方客户端。端点约定：</p>
 * <ul>
 *   <li>写入：{@code PUT /collections/{collection}/points}（body: {@code points:[{id,vector,payload}]}）；</li>
 *   <li>检索：{@code POST /collections/{collection}/points/search}
 *       （body: {@code vector, limit, filter, with_payload, with_vector}）；</li>
 *   <li>删除：{@code POST /collections/{collection}/points/delete}（body: {@code points:[id]}）；</li>
 *   <li>计数：{@code POST /collections/{collection}/points/count}（body: {@code exact:true}）；</li>
 *   <li>建集合：{@code PUT /collections/{collection}}（body: {@code vectors:{size,distance}}）。</li>
 * </ul>
 *
 * <p>鉴权：api-key 模式通过 {@code api-key} 请求头传递。Qdrant cosine/dot 的 {@code score}
 * 本身即相似度（越大越相似），直接作为结果得分；Euclid 距离映射为 {@code 1/(1+distance)}。</p>
 *
 * <p>元数据过滤：通过 {@link #similaritySearch(float[], int, FilterExpression)} 传入
 * {@link FilterExpression}，由 {@link QdrantFilterTranslator} 翻译为 Qdrant {@code filter} 结构。
 * 集合默认不自动创建，可调用 {@link #createCollection()} 或开启 {@code autoCreateCollection}。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class QdrantVectorStore implements VectorStore {

	/** 默认 REST 基础地址。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:6333";

	private final String baseUrl;
	private final String apiKey;
	private final String collectionName;
	private final int dimension;
	private final Distance distance;
	private final boolean autoCreateCollection;
	private final HttpClient httpClient;
	private final Duration timeout;
	private final QdrantFilterTranslator filterTranslator = new QdrantFilterTranslator();

	/**
	 * 私有构造器，使用 {@link Builder} 创建。
	 *
	 * @param b 构建器
	 */
	private QdrantVectorStore(Builder b) {
		this.baseUrl = trimSlash(Assert.notBlank(b.baseUrl, "baseUrl 不能为空"));
		this.apiKey = b.apiKey;
		this.collectionName = Assert.notBlank(b.collectionName, "collectionName 不能为空");
		this.dimension = b.dimension;
		this.distance = b.distance == null ? Distance.COSINE : b.distance;
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
		JsonArray points = Json.array();
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			JsonObject point = Json.object();
			point.set("id", v.id());
			point.set("vector", toList(v.embedding()));
			JsonObject payload = Json.object();
			payload.set("text", v.text() == null ? "" : v.text());
			for (Map.Entry<String, String> e : v.metadata().entrySet()) {
				payload.set(e.getKey(), e.getValue());
			}
			point.set("payload", payload);
			points.set(point);
		}
		JsonObject body = Json.object();
		body.set("points", points);
		put(pointsPath() + "?wait=true", body);
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		JsonObject body = Json.object();
		body.set("points", List.of(id));
		post(pointsPath() + "/delete", body);
		return true;
	}

	@Override
	public void clear() {
		// Qdrant 无 truncate：drop 集合后按需重建，实现"清空全部向量"。
		deleteCollection();
		if (autoCreateCollection) {
			createCollection();
		}
	}

	@Override
	public int size() {
		if (dimension <= 0) {
			return -1;
		}
		JsonObject body = Json.object();
		body.set("exact", true);
		JsonObject resp = post(collectionPath() + "/points/count", body);
		if (resp.has("result") && resp.get("result").isObject()) {
			return resp.getJsonObject("result").optInt("count", -1);
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
		JsonObject body = Json.object();
		body.set("vector", toList(queryEmbedding));
		body.set("limit", topK);
		body.set("with_payload", true);
		body.set("with_vector", true);
		JsonObject translated = filterTranslator.translate(filter);
		if (translated != null) {
			body.set("filter", translated);
		}
		JsonObject resp = post(pointsPath() + "/search", body);
		JsonArray hits = resp.has("result") ? resp.getJsonArray("result") : new JsonArray();
		List<SimilaritySearchResult> results = new ArrayList<>(hits.size());
		for (int i = 0; i < hits.size(); i++) {
			JsonObject hit = hits.getJsonObject(i);
			double rawScore = hit.optDouble("score", 0d);
			double score = mapScore(rawScore);
			if (score < minScore) {
				continue;
			}
			String id = hit.get("id").getAsString();
			JsonObject payload = hit.has("payload") && hit.get("payload").isObject()
					? hit.getJsonObject("payload") : new JsonObject();
			String text = payload.optString("text", "");
			Map<String, String> metadata = toStringMap(payload);
			float[] vector = hit.has("vector") && hit.get("vector").isArray()
					? toFloatArray(hit.getJsonArray("vector")) : null;
			results.add(new SimilaritySearchResult(id, vector, text, metadata, score));
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * 创建集合（best-effort）：已存在时 Qdrant 返回业务错误，此处忽略不抛。
	 *
	 * @return 是否成功（2xx 视为成功）
	 */
	public boolean createCollection() {
		if (dimension <= 0) {
			throw new AiException("Qdrant 创建集合需要 dimension，请通过 Builder.dimension 设置");
		}
		JsonObject vectors = Json.object();
		vectors.set("size", dimension);
		vectors.set("distance", distance.restName());
		JsonObject body = Json.object();
		body.set("vectors", vectors);
		put(collectionPath(), body, true);
		return true;
	}

	/**
	 * 删除集合（best-effort）。
	 */
	private void deleteCollection() {
		HttpRequest.Builder req = HttpRequest.newBuilder()
				.uri(URI.create(collectionPath()))
				.timeout(timeout)
				.DELETE();
		applyAuth(req);
		exchange(req);
	}

	private double mapScore(double rawScore) {
		return switch (distance) {
			case COSINE, DOT -> rawScore;
			case EUCLID -> 1d / (1d + Math.abs(rawScore));
		};
	}

	private String collectionPath() {
		return baseUrl + "/collections/" + collectionName;
	}

	private String pointsPath() {
		return collectionPath() + "/points";
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

	private static Map<String, String> toStringMap(JsonObject payload) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String key : payload.keySet()) {
			if ("text".equals(key)) {
				continue;
			}
			if (payload.get(key) != null && payload.get(key).isString()) {
				map.put(key, payload.get(key).getAsString());
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

	private JsonObject put(String path, JsonObject body) {
		return put(path, body, false);
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

	private JsonObject parse(HttpResponse<String> resp) {
		if (resp.statusCode() >= 400) {
			throw new AiException("Qdrant REST 返回错误 status=" + resp.statusCode()
					+ ", body=" + resp.body());
		}
		String text = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
		return (JsonObject) Json.parse(text);
	}

	private void applyAuth(HttpRequest.Builder builder) {
		if (apiKey != null && !apiKey.isBlank()) {
			builder.header("api-key", apiKey);
		}
	}

	private HttpResponse<String> exchange(HttpRequest.Builder builder) {
		return exchange(builder.build());
	}

	private HttpResponse<String> exchange(HttpRequest request) {
		try {
			return httpClient.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("Qdrant REST 调用失败: " + request.uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Qdrant REST 调用被中断: " + request.uri(), e);
		}
	}

	private static String trimSlash(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * 距离度量（用于建集合时声明）。
	 */
	public enum Distance {
		/** 余弦相似度（默认），score 即相似度。 */
		COSINE("Cosine"),
		/** 内积，score 即内积。 */
		DOT("Dot"),
		/** 欧氏距离，映射为 1/(1+|distance|)。 */
		EUCLID("Euclid");

		private final String restName;

		Distance(String restName) {
			this.restName = restName;
		}

		/**
		 * 返回 Qdrant 集合 distance 字段名。
		 *
		 * @return distance 名
		 */
		public String restName() {
			return restName;
		}
	}

	/**
	 * {@link QdrantVectorStore} 构建器。
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;
		private String apiKey;
		private String collectionName;
		private int dimension;
		private Distance distance = Distance.COSINE;
		private boolean autoCreateCollection;
		private HttpClient httpClient;
		private Duration timeout;

		/** 私有构造器。 */
		private Builder() {
		}

		/**
		 * 设置基础 URL。
		 *
		 * @param baseUrl 如 http://localhost:6333
		 * @return this
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * 设置 API Key，为空则不发送 api-key 头。
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
		 * 设置距离度量，默认 COSINE。
		 *
		 * @param distance 距离度量
		 * @return this
		 */
		public Builder distance(Distance distance) {
			this.distance = distance;
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
		 * @return QdrantVectorStore
		 */
		public QdrantVectorStore build() {
			return new QdrantVectorStore(this);
		}
	}
}
