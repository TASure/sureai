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
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.PineconeFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 Pinecone data-plane REST API 的外部向量库实现。
 *
 * <p>仅依赖 JDK {@link HttpClient}，不引入 Pinecone 官方 SDK。{@code baseUrl} 为
 * 索引主机名（如 {@code https://my-index-xxx.svc.pinecone.io}），鉴权通过 {@code Api-Key} 头。</p>
 *
 * <p>端点约定：</p>
 * <ul>
 *   <li>写入：{@code POST /vectors/upsert}（body: {@code vectors:[{id,values,metadata}], namespace}）；</li>
 *   <li>检索：{@code POST /query}（body: {@code vector, topK, filter, includeMetadata, includeValues}）；</li>
 *   <li>删除：{@code POST /vectors/delete}（body: {@code ids:[...], namespace}）；</li>
 *   <li>清空：同上传 {@code deleteAll:true}。</li>
 * </ul>
 *
 * <p>Pinecone cosine 索引返回的 {@code score} 即相似度（越大越相似），直接作为结果得分。
 * 原始文本存入 metadata 的 {@code text} 键。{@link #size()} 在 data-plane 无计数端点，固定返回 -1。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class PineconeVectorStore implements VectorStore {

	/** 默认 REST 基础地址（本地/占位）。 */
	public static final String DEFAULT_BASE_URL = "http://localhost";

	private final String baseUrl;
	private final String apiKey;
	private final String namespace;
	private final HttpClient httpClient;
	private final Duration timeout;
	private final PineconeFilterTranslator filterTranslator = new PineconeFilterTranslator();

	/**
	 * 私有构造器，使用 {@link Builder} 创建。
	 *
	 * @param b 构建器
	 */
	private PineconeVectorStore(Builder b) {
		this.baseUrl = trimSlash(Assert.notBlank(b.baseUrl, "baseUrl 不能为空"));
		this.apiKey = b.apiKey;
		this.namespace = b.namespace;
		this.httpClient = b.httpClient == null ? HttpClient.newHttpClient() : b.httpClient;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
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
		JsonArray vectors = Json.array();
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			JsonObject item = Json.object();
			item.set("id", v.id());
			item.set("values", toList(v.embedding()));
			JsonObject metadata = Json.object();
			metadata.set("text", v.text() == null ? "" : v.text());
			for (Map.Entry<String, String> e : v.metadata().entrySet()) {
				metadata.set(e.getKey(), e.getValue());
			}
			item.set("metadata", metadata);
			vectors.set(item);
		}
		JsonObject body = Json.object();
		body.set("vectors", vectors);
		applyNamespace(body);
		post("/vectors/upsert", body);
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		JsonObject body = Json.object();
		body.set("ids", List.of(id));
		applyNamespace(body);
		post("/vectors/delete", body);
		return true;
	}

	@Override
	public void clear() {
		JsonObject body = Json.object();
		body.set("deleteAll", true);
		applyNamespace(body);
		post("/vectors/delete", body);
	}

	@Override
	public int size() {
		// Pinecone data-plane 无逐命名空间计数端点，固定返回 -1。
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
		body.set("topK", topK);
		body.set("includeMetadata", true);
		body.set("includeValues", true);
		JsonObject translated = filterTranslator.translate(filter);
		if (translated != null) {
			body.set("filter", translated);
		}
		applyNamespace(body);
		JsonObject resp = post("/query", body);
		JsonArray matches = resp.has("matches") ? resp.getJsonArray("matches") : new JsonArray();
		List<SimilaritySearchResult> results = new ArrayList<>(matches.size());
		for (int i = 0; i < matches.size(); i++) {
			JsonObject m = matches.getJsonObject(i);
			double score = m.optDouble("score", 0d);
			if (score < minScore) {
				continue;
			}
			String id = m.getString("id");
			Map<String, String> metadata = m.has("metadata") && m.get("metadata").isObject()
					? toStringMap(m.getJsonObject("metadata")) : new LinkedHashMap<>();
			String text = metadata.getOrDefault("text", "");
			metadata.remove("text");
			float[] values = m.has("values") && m.get("values").isArray()
					? toFloatArray(m.getJsonArray("values")) : null;
			results.add(new SimilaritySearchResult(id, values, text, metadata, score));
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	private void applyNamespace(JsonObject body) {
		if (namespace != null && !namespace.isBlank()) {
			body.set("namespace", namespace);
		}
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
			JsonElement el = obj.get(key);
			if (el != null && el.isString()) {
				map.put(key, el.getAsString());
			}
		}
		return map;
	}

	private JsonObject post(String path, JsonObject body) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + path))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
		if (apiKey != null && !apiKey.isBlank()) {
			builder.header("Api-Key", apiKey);
		}
		HttpResponse<String> resp;
		try {
			resp = httpClient.send(builder.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("Pinecone REST 调用失败: " + baseUrl + path, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Pinecone REST 调用被中断: " + baseUrl + path, e);
		}
		if (resp.statusCode() >= 400) {
			throw new AiException("Pinecone REST 返回错误 status=" + resp.statusCode()
					+ ", body=" + resp.body());
		}
		String text = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
		return (JsonObject) Json.parse(text);
	}

	private static String trimSlash(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * {@link PineconeVectorStore} 构建器。
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;
		private String apiKey;
		private String namespace;
		private HttpClient httpClient;
		private Duration timeout;

		/** 私有构造器。 */
		private Builder() {
		}

		/**
		 * 设置索引主机 URL。
		 *
		 * @param baseUrl 如 https://my-index-xxx.svc.pinecone.io
		 * @return this
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * 设置 API Key（Api-Key 头），为空则不发送鉴权头。
		 *
		 * @param apiKey API Key
		 * @return this
		 */
		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		/**
		 * 设置命名空间，为空则使用默认 namespace。
		 *
		 * @param namespace 命名空间
		 * @return this
		 */
		public Builder namespace(String namespace) {
			this.namespace = namespace;
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
		 * @return PineconeVectorStore
		 */
		public PineconeVectorStore build() {
			return new PineconeVectorStore(this);
		}
	}
}
