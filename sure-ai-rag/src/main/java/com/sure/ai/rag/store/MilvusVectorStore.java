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
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.tool.lang.Assert;

/**
 * 基于 Milvus 2.x RESTful API（v2 vectordb 端点）的外部向量库实现。
 *
 * <p>仅依赖 JDK {@link HttpClient}，不引入任何 Milvus 官方 SDK。
 * 通过 HTTP 调用 Milvus 的 entities/collections 端点完成写入、检索与删除。</p>
 *
 * <p><b>距离 → 相似度映射</b>（由 {@code metricType} 决定）：</p>
 * <ul>
 *   <li>COSINE（默认）：Milvus 返回的 distance 即余弦相似度，直接作为 score；</li>
 *   <li>IP（内积）：distance 即内积，直接作为 score；</li>
 *   <li>L2（欧氏距离）：distance 越小越相似，映射为 {@code score = 1 / (1 + distance)}。</li>
 * </ul>
 *
 * <p>注意：{@link #size()} 在 v2 REST 协议下无可靠的逐集合计数端点，固定返回 {@code -1}。
 * {@link #clear()} 通过 drop 集合后按需重建实现，集合内全部历史数据被删除。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public class MilvusVectorStore implements VectorStore {

	/** 默认 REST 基础地址。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:19531";

	private static final String PATH_CREATE = "/v2/vectordb/collections/create";
	private static final String PATH_DROP = "/v2/vectordb/collections/drop";
	private static final String PATH_INSERT = "/v2/vectordb/entities/insert";
	private static final String PATH_SEARCH = "/v2/vectordb/entities/search";
	private static final String PATH_DELETE = "/v2/vectordb/entities/delete";

	private final String baseUrl;
	private final String apiKey;
	private final String collectionName;
	private final int dimension;
	private final MetricType metricType;
	private final boolean autoCreateCollection;
	private final HttpClient httpClient;
	private final Duration timeout;

	/**
	 * 私有构造器，使用 {@link Builder} 创建。
	 *
	 * @param b 构建器
	 */
	private MilvusVectorStore(Builder b) {
		this.baseUrl = trimSlash(Assert.notBlank(b.baseUrl, "baseUrl 不能为空"));
		this.apiKey = b.apiKey;
		this.collectionName = Assert.notBlank(b.collectionName, "collectionName 不能为空");
		this.dimension = b.dimension;
		Assert.isTrue(dimension > 0, "dimension 必须大于 0，实际为 {}", dimension);
		this.metricType = b.metricType == null ? MetricType.COSINE : b.metricType;
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
		JsonArray rows = Json.array();
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			JsonObject row = Json.object();
			row.set("id", v.id());
			row.set("vector", toList(v.embedding()));
			row.set("text", v.text() == null ? "" : v.text());
			for (Map.Entry<String, String> e : v.metadata().entrySet()) {
				row.set(e.getKey(), e.getValue());
			}
			rows.set(row);
		}
		JsonObject body = Json.object();
		body.set("collectionName", collectionName);
		body.set("data", rows);
		post(PATH_INSERT, body);
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		JsonObject body = Json.object();
		body.set("collectionName", collectionName);
		body.set("filter", "id in [\"" + id + "\"]");
		JsonObject resp = post(PATH_DELETE, body);
		return resp.optInt("code", 0) == 0;
	}

	@Override
	public void clear() {
		// v2 REST 无 truncate 端点：drop 集合后按需重建，实现"清空全部向量"。
		post(PATH_DROP, bodyOfName());
		if (autoCreateCollection) {
			createCollection();
		}
	}

	@Override
	public int size() {
		// v2 vectordb REST 协议未暴露可靠的逐集合实体计数，固定返回 -1。
		return -1;
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore) {
		Assert.notNull(queryEmbedding, "queryEmbedding 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		JsonObject body = Json.object();
		body.set("collectionName", collectionName);
		body.set("data", List.of(toList(queryEmbedding)));
		body.set("limit", topK);
		body.set("outputFields", List.of("id", "vector", "text", "*"));
		JsonObject resp = post(PATH_SEARCH, body);
		JsonArray hits = resp.has("data") ? resp.getJsonArray("data") : new JsonArray();
		List<SimilaritySearchResult> results = new ArrayList<>(hits.size());
		for (int i = 0; i < hits.size(); i++) {
			JsonObject hit = hits.getJsonObject(i);
			double rawDistance = hit.optDouble("distance", hit.optDouble("score", 0d));
			double score = mapScore(rawDistance);
			if (score < minScore) {
				continue;
			}
			results.add(new SimilaritySearchResult(
					hit.get("id").getAsString(),
					parseVector(hit),
					hit.optString("text", ""),
					parseMetadata(hit),
					score));
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * 创建集合（best-effort）：已存在时 Milvus 返回业务错误码，此处忽略，不抛出。
	 */
	private void createCollection() {
		JsonObject body = Json.object();
		body.set("collectionName", collectionName);
		body.set("dimension", dimension);
		body.set("metricType", metricType.restName());
		post(PATH_CREATE, body, true);
	}

	private JsonObject bodyOfName() {
		JsonObject body = Json.object();
		body.set("collectionName", collectionName);
		return body;
	}

	/**
	 * 将原始距离映射为统一的相似度得分。
	 *
	 * @param rawDistance Milvus 返回的 distance/score
	 * @return 相似度得分（越大越相似）
	 */
	private double mapScore(double rawDistance) {
		return switch (metricType) {
			case COSINE, IP -> rawDistance;
			case L2 -> 1d / (1d + rawDistance);
		};
	}

	private static List<Double> toList(float[] vector) {
		List<Double> list = new ArrayList<>(vector.length);
		for (float f : vector) {
			list.add((double) f);
		}
		return list;
	}

	private static float[] parseVector(JsonObject hit) {
		if (!hit.has("vector")) {
			return null;
		}
		JsonArray arr = hit.getJsonArray("vector");
		float[] vector = new float[arr.size()];
		for (int i = 0; i < arr.size(); i++) {
			vector[i] = (float) arr.get(i).getAsDouble();
		}
		return vector;
	}

	private static Map<String, String> parseMetadata(JsonObject hit) {
		Map<String, String> metadata = new LinkedHashMap<>();
		for (String key : hit.keySet()) {
			if (key.equals("id") || key.equals("vector") || key.equals("text")
					|| key.equals("distance") || key.equals("score")) {
				continue;
			}
			JsonElement el = hit.get(key);
			if (el != null && el.isString()) {
				metadata.put(key, el.getAsString());
			}
		}
		return metadata;
	}

	private JsonObject post(String path, JsonObject body) {
		return post(path, body, false);
	}

	/**
	 * 发送 POST JSON 请求并解析响应。
	 *
	 * @param path           相对路径
	 * @param body           请求体
	 * @param tolerateError  为 true 时业务/HTTP 错误不抛出（用于幂等的集合创建）
	 * @return 解析后的响应对象
	 */
	private JsonObject post(String path, JsonObject body, boolean tolerateError) {
		String url = baseUrl + path;
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
		if (apiKey != null && !apiKey.isBlank()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}
		HttpResponse<String> resp;
		try {
			resp = httpClient.send(builder.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("Milvus REST 调用失败: " + url, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Milvus REST 调用被中断: " + url, e);
		}
		if (resp.statusCode() >= 400) {
			if (tolerateError) {
				return new JsonObject();
			}
			throw new AiException("Milvus REST 返回错误 status=" + resp.statusCode()
					+ ", body=" + resp.body());
		}
		String text = resp.body() == null ? "{}" : resp.body();
		return (JsonObject) Json.parse(text);
	}

	private static String trimSlash(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * 距离度量类型。
	 */
	public enum MetricType {
		/** 余弦相似度（默认，distance 即相似度）。 */
		COSINE("COSINE"),
		/** 内积（distance 即内积得分）。 */
		IP("IP"),
		/** 欧氏距离（映射为 1/(1+distance)）。 */
		L2("L2");

		private final String restName;

		MetricType(String restName) {
			this.restName = restName;
		}

		/**
		 * 返回 Milvus REST 使用的度量名。
		 *
		 * @return 度量名
		 */
		public String restName() {
			return restName;
		}
	}

	/**
	 * {@link MilvusVectorStore} 构建器。
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;
		private String apiKey;
		private String collectionName;
		private int dimension;
		private MetricType metricType = MetricType.COSINE;
		private boolean autoCreateCollection = true;
		private HttpClient httpClient;
		private Duration timeout;

		/** 私有构造器。 */
		private Builder() {
		}

		/**
		 * 设置基础 URL。
		 *
		 * @param baseUrl 如 http://localhost:19531
		 * @return this
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * 设置 API Key（Milvus 2.3+），为空则不发送鉴权头。
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
		 * 设置向量维度（必需，&gt;0）。
		 *
		 * @param dimension 维度
		 * @return this
		 */
		public Builder dimension(int dimension) {
			this.dimension = dimension;
			return this;
		}

		/**
		 * 设置距离度量类型，默认 COSINE。
		 *
		 * @param metricType 度量类型
		 * @return this
		 */
		public Builder metricType(MetricType metricType) {
			this.metricType = metricType;
			return this;
		}

		/**
		 * 是否构造时自动创建集合，默认 true。
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
		 * @return MilvusVectorStore
		 */
		public MilvusVectorStore build() {
			return new MilvusVectorStore(this);
		}
	}
}
