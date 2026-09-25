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

package com.sure.ai.client.compat;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.TokenUsage;

/**
 * Embedding 能力域策略：{@code /embeddings} 请求构建与向量数组响应解析。
 *
 * @author sureai
 * @since 1.4.0
 */
final class EmbeddingCompatStrategy {

	/** 持有外层客户端引用，用于传输与 usage 回传。 */
	private final OpenAiCompatClient client;

	/**
	 * @param client 外层 OpenAI 兼容客户端
	 */
	EmbeddingCompatStrategy(OpenAiCompatClient client) {
		this.client = client;
	}

	/** 向量入口。 */
	EmbeddingResponse embed(EmbeddingRequest request) {
		JsonObject body = Json.object();
		body.put("model", request.model());
		JsonArray input = Json.array();
		for (String s : request.input()) {
			input.add(s);
		}
		body.put("input", input);
		JsonObject resp = this.client.transportPost(this.client.embeddingsPath, body);
		return parseEmbeddingResponse(resp);
	}

	/** 解析向量响应。 */
	private EmbeddingResponse parseEmbeddingResponse(JsonObject resp) {
		String model = resp.optString("model", null);
		JsonArray data = resp.getJsonArray("data");
		List<float[]> embeddings = new ArrayList<>();
		for (int i = 0; i < data.size(); i++) {
			JsonObject d = data.getJsonObject(i);
			JsonArray emb = d.getJsonArray("embedding");
			float[] vec = new float[emb.size()];
			for (int j = 0; j < emb.size(); j++) {
				vec[j] = (float) emb.getDouble(j);
			}
			embeddings.add(vec);
		}
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
				u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
			this.client.notifyUsage(model, usage.promptTokens(), usage.completionTokens(),
				usage.totalTokens());
		}
		return EmbeddingResponse.of(model, embeddings, usage);
	}
}
