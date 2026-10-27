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

package com.sure.ai.qwen;

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
 * 阿里云百炼通义千问（DashScope）重排（Rerank）客户端。
 *
 * <p>走 OpenAI 兼容模式：默认 baseUrl 为
 * {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，端点 {@code POST /reranks}，
 * 鉴权为 {@code Authorization: Bearer <apiKey>}。</p>
 *
 * <p>请求体：{@code {"model":..., "query":..., "documents":[...], "top_n":...}}，
 * 其中 {@code top_n} 仅在请求显式设置时携带。</p>
 *
 * <p>响应体：{@code {"results":[{"index":0,"relevance_score":0.95,
 * "document":{"text":"..."}}]}}，每项的 {@code document.text} 还原为命中文档原文。</p>
 *
 * <p>官方文档：<a href="https://help.aliyun.com/zh/model-structure/text-rerank">文本重排</a></p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenRerankClient extends AbstractAiClient implements RerankClient {

	/** DashScope 兼容模式默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

	/** 重排接口路径（相对兼容模式 baseUrl）。 */
	private static final String RERANKS_PATH = "/reranks";

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public QwenRerankClient(AiConfig config) {
		super(config.withBaseUrlIfAbsent(DEFAULT_BASE_URL));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "qwen-rerank"
	 */
	public String name() {
		return "qwen-rerank";
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
		PostResult result = doPostRaw(RERANKS_PATH, body);
		return parseResponse(request.model(), result.json(), result.rawBody());
	}

	/** 解析响应：{results:[{index, relevance_score, document:{text}}]}。 */
	private static RerankResponse parseResponse(String model, JsonObject resp, String rawJson) {
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
}
