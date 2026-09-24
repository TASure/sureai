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
import com.sure.tool.lang.Assert;

/**
 * 基于 Chroma REST API（v1）的外部向量库实现。
 *
 * <p>仅依赖 JDK {@link HttpClient}，不引入任何 Chroma 官方客户端库。
 * 构造时按名 get-or-create 集合并缓存 {@code collection_id}，后续读写均走该集合。</p>
 *
 * <p><b>距离 → 相似度映射</b>（由集合 {@code hnsw:space} 决定）：</p>
 * <ul>
 *   <li>L2（默认）：Chroma 返回 L2 平方距离，映射为 {@code score = 1 / (1 + distance)}；</li>
 *   <li>COSINE：distance 为余弦距离，映射为 {@code score = 1 - distance}。</li>
 * </ul>
 *
 * <p>注意：{@link #size()} 通过读取集合元信息中的 {@code segments[:-1].state} 不可靠，
 * 此处固定返回 {@code -1}。{@link #clear()} 调用 delete 端点删除全部已写入 id，
 * 未在本实例缓存的历史 id 不会被清除。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public class ChromaVectorStore implements VectorStore {

	/** 默认 REST 基础地址。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:8000";

	private final String baseUrl;
	private final String token;
	private final String collectionName;
	private final DistanceFunction distanceFunction;
	private final boolean autoCreateCollection;
	private final HttpClient httpClient;
	private final Duration timeout;

	/** 缓存的集合 ID（构造时 get-or-create 获得）。 */
	private String collectionId;

	/**
	 * 私有构造器，使用 {@link Builder} 创建。
	 *
	 * @param b 构建器
	 */
	private ChromaVectorStore(Builder b) {
		this.baseUrl = trimSlash(Assert.notBlank(b.baseUrl, "baseUrl 不能为空"));
		this.token = b.token;
		this.collectionName = Assert.notBlank(b.collectionName, "collectionName 不能为空");
		this.distanceFunction = b.distanceFunction == null
				? DistanceFunction.L2 : b.distanceFunction;
		this.autoCreateCollection = b.autoCreateCollection;
		this.httpClient = b.httpClient == null ? HttpClient.newHttpClient() : b.httpClient;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		this.collectionId = resolveCollectionId();
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
		List<String> ids = new ArrayList<>(batch.size());
		List<List<Double>> embeddings = new ArrayList<>(batch.size());
		List<String> documents = new ArrayList<>(batch.size());
		List<Map<String, String>> metadatas = new ArrayList<>(batch.size());
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			ids.add(v.id());
			embeddings.add(toList(v.embedding()));
			documents.add(v.text() == null ? "" : v.text());
			metadatas.add(v.metadata());
		}
		JsonObject body = Json.object();
		body.set("ids", ids);
		body.set("embeddings", embeddings);
		body.set("documents", documents);
		body.set("metadatas", metadatas);
		post(collectionPath() + "/add", body);
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		JsonObject body = Json.object();
		body.set("ids", List.of(id));
		post(collectionPath() + "/delete", body);
		return true;
	}

	@Override
	public void clear() {
		// Chroma 无 truncate 端点；本实例未持久化全量 id，无法精准清空远端全部数据。
		// 这里删除集合并按需重建，语义为"清空"。
		HttpRequest.Builder req = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/api/v1/collections/" + collectionId))
				.timeout(timeout)
				.DELETE();
		applyAuth(req);
		exchange(req);
		if (autoCreateCollection) {
			this.collectionId = resolveCollectionId();
		}
	}

	@Override
	public int size() {
		// Chroma v1 REST 未在本实现中缓存全量 id 集合，固定返回 -1。
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
		body.set("query_embeddings", List.of(toList(queryEmbedding)));
		body.set("n_results", topK);
		body.set("include", List.of("distances", "documents", "metadatas", "embeddings"));
		JsonObject resp = post(collectionPath() + "/query", body);

		JsonArray idGroup = resp.has("ids") ? resp.getJsonArray("ids").getJsonArray(0)
				: new JsonArray();
		JsonArray distGroup = resp.has("distances")
				? resp.getJsonArray("distances").getJsonArray(0) : new JsonArray();
		JsonArray docGroup = resp.has("documents")
				? resp.getJsonArray("documents").getJsonArray(0) : new JsonArray();
		JsonArray metaGroup = resp.has("metadatas")
				? resp.getJsonArray("metadatas").getJsonArray(0) : new JsonArray();
		JsonArray embGroup = resp.has("embeddings")
				? resp.getJsonArray("embeddings").getJsonArray(0) : new JsonArray();

		List<SimilaritySearchResult> results = new ArrayList<>(idGroup.size());
		for (int i = 0; i < idGroup.size(); i++) {
			double distance = i < distGroup.size() ? distGroup.get(i).getAsDouble() : 0d;
			double score = mapScore(distance);
			if (score < minScore) {
				continue;
			}
			String id = idGroup.get(i).getAsString();
			String text = i < docGroup.size() && !docGroup.get(i).isNull()
					? docGroup.get(i).getAsString() : "";
			Map<String, String> metadata = i < metaGroup.size() && metaGroup.get(i).isObject()
					? toStringMap(metaGroup.getJsonObject(i)) : new LinkedHashMap<>();
			float[] embedding = i < embGroup.size() && embGroup.get(i).isArray()
					? toFloatArray(embGroup.getJsonArray(i)) : null;
			results.add(new SimilaritySearchResult(id, embedding, text, metadata, score));
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * get-or-create 集合，返回 collection_id。
	 *
	 * @return 集合 ID
	 */
	private String resolveCollectionId() {
		HttpResponse<String> resp = exchange(HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/api/v1/collections/" + collectionName))
				.timeout(timeout)
				.GET().build());
		if (resp.statusCode() == 200) {
			return Json.parse(resp.body()).getAsJsonObject().getString("id");
		}
		if (resp.statusCode() == 404 && autoCreateCollection) {
			JsonObject body = Json.object();
			body.set("name", collectionName);
			JsonObject metadata = Json.object();
			metadata.set("hnsw:space", distanceFunction.restName());
			body.set("metadata", metadata);
			JsonObject created = post(baseUrl + "/api/v1/collections", body);
			return created.getString("id");
		}
		throw new AiException("Chroma 获取集合失败 status=" + resp.statusCode()
				+ ", body=" + resp.body());
	}

	private String collectionPath() {
		return baseUrl + "/api/v1/collections/" + collectionId;
	}

	/**
	 * 将 Chroma 距离映射为相似度得分。
	 *
	 * @param distance 原始距离
	 * @return 相似度（越大越相似）
	 */
	private double mapScore(double distance) {
		return switch (distanceFunction) {
			case L2 -> 1d / (1d + distance);
			case COSINE -> 1d - distance;
		};
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

	private static Map<String, String> toStringMap(JsonObject obj) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String key : obj.keySet()) {
			if (obj.get(key) != null && obj.get(key).isString()) {
				map.put(key, obj.get(key).getAsString());
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
		HttpResponse<String> resp = exchange(builder);
		if (resp.statusCode() >= 400) {
			throw new AiException("Chroma REST 返回错误 status=" + resp.statusCode()
					+ ", body=" + resp.body());
		}
		String text = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
		return (JsonObject) Json.parse(text);
	}

	private void applyAuth(HttpRequest.Builder builder) {
		if (token != null && !token.isBlank()) {
			builder.header("X-Chroma-Token", token);
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
			throw new AiException("Chroma REST 调用失败: " + request.uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Chroma REST 调用被中断: " + request.uri(), e);
		}
	}

	private static String trimSlash(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * 距离度量函数。
	 */
	public enum DistanceFunction {
		/** L2 平方距离（默认），映射为 1/(1+distance)。 */
		L2("l2"),
		/** 余弦距离，映射为 1-distance。 */
		COSINE("cosine");

		private final String restName;

		DistanceFunction(String restName) {
			this.restName = restName;
		}

		/**
		 * 返回 Chroma 集合 metadata 使用的空间名。
		 *
		 * @return 空间名
		 */
		public String restName() {
			return restName;
		}
	}

	/**
	 * {@link ChromaVectorStore} 构建器。
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;
		private String token;
		private String collectionName;
		private DistanceFunction distanceFunction = DistanceFunction.L2;
		private boolean autoCreateCollection = true;
		private HttpClient httpClient;
		private Duration timeout;

		/** 私有构造器。 */
		private Builder() {
		}

		/**
		 * 设置基础 URL。
		 *
		 * @param baseUrl 如 http://localhost:8000
		 * @return this
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * 设置 Chroma 鉴权 Token，为空则不发送鉴权头。
		 *
		 * @param token Token
		 * @return this
		 */
		public Builder token(String token) {
			this.token = token;
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
		 * 设置距离函数，默认 L2。
		 *
		 * @param distanceFunction 距离函数
		 * @return this
		 */
		public Builder distanceFunction(DistanceFunction distanceFunction) {
			this.distanceFunction = distanceFunction;
			return this;
		}

		/**
		 * 集合不存在时是否自动创建，默认 true。
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
		 * @return ChromaVectorStore
		 */
		public ChromaVectorStore build() {
			return new ChromaVectorStore(this);
		}
	}
}
