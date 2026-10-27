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
import java.util.UUID;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.filter.FilterExpression;
import com.sure.ai.rag.store.filter.WeaviateFilterTranslator;
import com.sure.tool.lang.Assert;

/**
 * 基于 Weaviate REST/GraphQL API 的外部向量库实现。
 *
 * <p>仅依赖 JDK {@link HttpClient}，不引入 Weaviate 官方客户端。{@code baseUrl} 为
 * Weaviate 实例地址（如 {@code http://localhost:8080}），WCD 托管实例通过
 * {@code Authorization: Bearer <apiKey>} 鉴权。</p>
 *
 * <p>端点约定：</p>
 * <ul>
 *   <li>写入：{@code POST /v1/objects}，批量 {@code POST /v1/batch/objects}
 *       （body: {@code objects:[{class,id,properties,vector}]}）；</li>
 *   <li>检索：{@code POST /v1/graphql}，使用 {@code nearVector} 搜索 + {@code where} 过滤；</li>
 *   <li>删除：{@code DELETE /v1/objects/{uuid}}；</li>
 *   <li>计数：GraphQL {@code Aggregate.{class}.meta.count}。</li>
 * </ul>
 *
 * <p>Weaviate 对象 id 必须为 UUID，业务 id 通过 name-based UUID（v3）确定性映射，
 * 并在属性 {@code _doc_id} 中保留原始 id，检索结果优先返回业务 id。原始文本存入
 * 可配置属性 {@code textProperty}（默认 {@code text}）。得分优先采用 {@code certainty}，
 * 缺失时把 {@code distance} 映射为 {@code 1/(1+distance)}。</p>
 *
 * <p>注意：GraphQL 需显式声明返回字段，本实现仅请求文本与 {@code _additional}，
 * 检索结果的 metadata 不回填（写入时仍按原生属性落库，过滤可正常生效）。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public class WeaviateVectorStore implements VectorStore {

	/** 默认 REST 基础地址。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:8080";

	/** 保留业务 id 的属性名。 */
	public static final String DOC_ID_PROPERTY = "_doc_id";

	private final String baseUrl;
	private final String apiKey;
	private final String className;
	private final String textProperty;
	private final boolean autoCreateSchema;
	private final HttpClient httpClient;
	private final Duration timeout;
	private final WeaviateFilterTranslator filterTranslator = new WeaviateFilterTranslator();

	/**
	 * 私有构造器，使用 {@link Builder} 创建。
	 *
	 * @param b 构建器
	 */
	private WeaviateVectorStore(Builder b) {
		this.baseUrl = trimSlash(Assert.notBlank(b.baseUrl, "baseUrl 不能为空"));
		this.apiKey = b.apiKey;
		this.className = Assert.notBlank(b.className, "className 不能为空");
		this.textProperty = b.textProperty == null || b.textProperty.isBlank()
				? "text" : b.textProperty;
		this.autoCreateSchema = b.autoCreateSchema;
		this.httpClient = b.httpClient == null ? HttpClient.newHttpClient() : b.httpClient;
		this.timeout = b.timeout == null ? Duration.ofSeconds(10) : b.timeout;
		if (this.autoCreateSchema) {
			createSchema();
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
		JsonObject body = objectBody(vector);
		post("/v1/objects", body);
	}

	@Override
	public void addAll(List<Vector> batch) {
		Assert.notNull(batch, "batch 不能为 null");
		if (batch.isEmpty()) {
			return;
		}
		JsonArray objects = Json.array();
		for (Vector v : batch) {
			if (v == null) {
				continue;
			}
			objects.set(objectBody(v));
		}
		JsonObject body = Json.object();
		body.set("objects", objects);
		post("/v1/batch/objects", body);
	}

	@Override
	public boolean delete(String id) {
		Assert.notBlank(id, "id 不能为 blank");
		HttpRequest.Builder req = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/v1/objects/" + toUuid(id)))
				.timeout(timeout)
				.DELETE();
		applyAuth(req);
		HttpResponse<String> resp = exchange(req);
		return resp.statusCode() >= 200 && resp.statusCode() < 300;
	}

	@Override
	public void clear() {
		// Weaviate 无 truncate：drop class 后按需重建，实现"清空全部向量"。
		HttpRequest.Builder req = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/v1/schema/" + className))
				.timeout(timeout)
				.DELETE();
		applyAuth(req);
		exchange(req);
		if (autoCreateSchema) {
			createSchema();
		}
	}

	@Override
	public int size() {
		String query = "query{Aggregate{" + className + "{meta{count}}}";
		JsonObject payload = Json.object();
		payload.set("query", query);
		JsonObject resp = post("/v1/graphql", payload);
		JsonObject data = resp.has("data") ? resp.getJsonObject("data") : new JsonObject();
		JsonObject agg = data.has("Aggregate") ? data.getJsonObject("Aggregate") : new JsonObject();
		if (agg.has(className) && agg.get(className).isArray()) {
			JsonArray arr = agg.getJsonArray(className);
			if (!arr.isEmpty() && arr.get(0).isObject()) {
				JsonObject meta = arr.getJsonObject(0).getJsonObject("meta");
				return meta.optInt("count", -1);
			}
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
		String query = "query(\n"
				+ "  $limit: Int!\n"
				+ "  $vec: [Float!]\n"
				+ "  $where: WhereFilter\n"
				+ ") {\n"
				+ "  Get {\n"
				+ "    " + className + "(\n"
				+ "      limit: $limit\n"
				+ "      nearVector: { vector: $vec }\n"
				+ "      where: $where\n"
				+ "    ) {\n"
				+ "      " + textProperty + "\n"
				+ "      " + DOC_ID_PROPERTY + "\n"
				+ "      _additional { id distance certainty vector }\n"
				+ "    }\n"
				+ "  }\n"
				+ "}";
		JsonObject payload = Json.object();
		payload.set("query", query);
		JsonObject variables = Json.object();
		variables.set("limit", topK);
		variables.set("vec", toList(queryEmbedding));
		JsonObject where = filterTranslator.translate(filter);
		variables.set("where", where == null ? null : where);
		payload.set("variables", variables);

		JsonObject resp = post("/v1/graphql", payload);
		List<SimilaritySearchResult> results = new ArrayList<>();
		JsonObject data = resp.has("data") ? resp.getJsonObject("data") : new JsonObject();
		JsonObject get = data.has("Get") ? data.getJsonObject("Get") : new JsonObject();
		if (get.has(className) && get.get(className).isArray()) {
			JsonArray hits = get.getJsonArray(className);
			for (int i = 0; i < hits.size(); i++) {
				JsonObject hit = hits.getJsonObject(i);
				JsonObject addl = hit.has("_additional") && hit.get("_additional").isObject()
						? hit.getJsonObject("_additional") : new JsonObject();
				double certainty = addl.optDouble("certainty", -1d);
				double distance = addl.optDouble("distance", 0d);
				double score = certainty >= 0d ? certainty : 1d / (1d + distance);
				if (score < minScore) {
					continue;
				}
				String docId = hit.optString(DOC_ID_PROPERTY, null);
				String id = docId != null ? docId : addl.optString("id", "");
				String text = hit.optString(textProperty, "");
				float[] vector = addl.has("vector") && addl.get("vector").isArray()
						? toFloatArray(addl.getJsonArray("vector")) : null;
				results.add(new SimilaritySearchResult(id, vector, text,
						new LinkedHashMap<>(), score));
			}
		}
		results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
		return results;
	}

	/**
	 * 创建 schema（best-effort）：已存在时忽略不抛。
	 *
	 * @return 是否成功
	 */
	public boolean createSchema() {
		JsonObject textProp = Json.object();
		textProp.set("name", textProperty);
		textProp.set("dataType", List.of("text"));
		JsonObject docIdProp = Json.object();
		docIdProp.set("name", DOC_ID_PROPERTY);
		docIdProp.set("dataType", List.of("text"));
		JsonObject body = Json.object();
		body.set("class", className);
		body.set("vectorizer", "none");
		body.set("properties", List.of(textProp, docIdProp));
		post("/v1/schema", body, true);
		return true;
	}

	private JsonObject objectBody(Vector v) {
		JsonObject properties = Json.object();
		properties.set(textProperty, v.text() == null ? "" : v.text());
		properties.set(DOC_ID_PROPERTY, v.id());
		for (Map.Entry<String, String> e : v.metadata().entrySet()) {
			properties.set(e.getKey(), e.getValue());
		}
		JsonObject body = Json.object();
		body.set("class", className);
		body.set("id", toUuid(v.id()));
		body.set("properties", properties);
		body.set("vector", toList(v.embedding()));
		return body;
	}

	private static String toUuid(String businessId) {
		return UUID.nameUUIDFromBytes(("sureai:" + businessId).getBytes(StandardCharsets.UTF_8))
				.toString();
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

	private JsonObject post(String path, JsonObject body) {
		return post(path, body, false);
	}

	private JsonObject post(String path, JsonObject body, boolean tolerateError) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + path))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
		applyAuth(builder);
		HttpResponse<String> resp = exchange(builder);
		if (tolerateError) {
			return new JsonObject();
		}
		if (resp.statusCode() >= 400) {
			throw new AiException("Weaviate REST 返回错误 status=" + resp.statusCode()
					+ ", body=" + resp.body());
		}
		String text = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
		return (JsonObject) Json.parse(text);
	}

	private void applyAuth(HttpRequest.Builder builder) {
		if (apiKey != null && !apiKey.isBlank()) {
			builder.header("Authorization", "Bearer " + apiKey);
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
			throw new AiException("Weaviate REST 调用失败: " + request.uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Weaviate REST 调用被中断: " + request.uri(), e);
		}
	}

	private static String trimSlash(String url) {
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * {@link WeaviateVectorStore} 构建器。
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;
		private String apiKey;
		private String className;
		private String textProperty = "text";
		private boolean autoCreateSchema;
		private HttpClient httpClient;
		private Duration timeout;

		/** 私有构造器。 */
		private Builder() {
		}

		/**
		 * 设置基础 URL。
		 *
		 * @param baseUrl 如 http://localhost:8080
		 * @return this
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * 设置 API Key（WCD 走 Authorization: Bearer），为空则不发送鉴权头。
		 *
		 * @param apiKey API Key
		 * @return this
		 */
		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		/**
		 * 设置 collection/class 名（必需）。
		 *
		 * @param className 类名
		 * @return this
		 */
		public Builder className(String className) {
			this.className = className;
			return this;
		}

		/**
		 * 设置原始文本属性名，默认 {@code text}。
		 *
		 * @param textProperty 文本属性名
		 * @return this
		 */
		public Builder textProperty(String textProperty) {
			this.textProperty = textProperty;
			return this;
		}

		/**
		 * 是否构造时自动创建 schema，默认 false。
		 *
		 * @param autoCreateSchema 是否自动创建
		 * @return this
		 */
		public Builder autoCreateSchema(boolean autoCreateSchema) {
			this.autoCreateSchema = autoCreateSchema;
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
		 * @return WeaviateVectorStore
		 */
		public WeaviateVectorStore build() {
			return new WeaviateVectorStore(this);
		}
	}
}
