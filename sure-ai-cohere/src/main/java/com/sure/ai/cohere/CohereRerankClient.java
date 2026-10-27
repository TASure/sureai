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

package com.sure.ai.cohere;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.RerankClient;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.RerankRequest;
import com.sure.ai.model.RerankResponse;
import com.sure.ai.model.RerankResult;

/**
 * Cohere v2 重排（Rerank）客户端（独立协议，非 OpenAI 兼容）。
 *
 * <p>默认 baseUrl 为 {@code https://api.cohere.com/v2}，端点 {@code POST /rerank}，
 * 鉴权为 {@code Authorization: Bearer <apiKey>}，与 {@link CohereClient} 一致。</p>
 *
 * <p>请求体：{@code {"model":..., "query":..., "documents":["..."], "top_n":...}}，
 * documents 先支持字符串列表（Cohere v2 同时支持对象列表，后续按需扩展）；
 * {@code top_n} 仅在请求显式设置时携带，缺省返回全部结果。</p>
 *
 * <p>响应体：{@code {"model":..., "results":[{"index":0, "relevance_score":0.95,
 * "document":{"text":"..."}}]}}，每项的 {@code document.text} 还原为命中文档原文。</p>
 *
 * <p>官方文档：<a href="https://docs.cohere.com/reference/rerank">Rerank</a></p>
 *
 * @author sureai
 * @since 1.3.0
 */
public class CohereRerankClient extends AbstractAiClient implements RerankClient {

	/** 重排接口路径（相对 baseUrl）。 */
	private static final String RERANK_PATH = "/rerank";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link CohereClient#DEFAULT_BASE_URL}。
	 *
	 * @param config 配置：apiKey 为 Cohere API Key
	 */
	public CohereRerankClient(AiConfig config) {
		super(withDefaults(config));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "cohere-rerank"
	 */
	public String name() {
		return "cohere-rerank";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
	}

	@Override
	public RerankResponse rerank(RerankRequest request) {
		JsonObject body = Json.object();
		body.put("model", request.model());
		body.put("query", request.query());
		JsonArray documents = Json.array();
		for (String doc : request.documents()) {
			documents.add(doc);
		}
		body.put("documents", documents);
		if (request.topN() != null) {
			body.put("top_n", request.topN());
		}
		for (java.util.Map.Entry<String, Object> e : request.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		PostResult result = doPostRaw(RERANK_PATH, body);
		return parseResponse(result.json(), result.rawBody(), request.model());
	}

	/** 解析响应：{results:[{index, relevance_score, document:{text}}]}。 */
	private static RerankResponse parseResponse(JsonObject resp, String rawJson, String requestModel) {
		String model = resp.optString("model", requestModel);
		List<RerankResult> results = new ArrayList<>();
		JsonArray arr = resp.has("results") ? resp.getJsonArray("results") : null;
		if (arr != null) {
			for (int i = 0; i < arr.size(); i++) {
				JsonObject item = arr.getJsonObject(i);
				int index = item.optInt("index", i);
				double score = item.optDouble("relevance_score", 0.0);
				String document = null;
				if (item.has("document") && item.get("document").isObject()) {
					document = item.getJsonObject("document").optString("text", null);
				}
				results.add(RerankResult.of(index, score, document, item.toString()));
			}
		}
		return RerankResponse.of(model, results, rawJson);
	}

	/** baseUrl 为空时补默认地址，其余配置通过 {@link AiConfig#withBaseUrl} 原样保留。 */
	private static AiConfig withDefaults(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(CohereClient.DEFAULT_BASE_URL);
	}
}
