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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;
import com.sure.ai.model.ModerationResult;

/**
 * Moderation 能力域策略：{@code /moderations} 请求构建与
 * {@code {id, model, results:[{flagged, category_scores, categories}]}} 响应解析。
 *
 * @author sureai
 * @since 1.4.0
 */
final class ModerationCompatStrategy {

	/** 持有外层客户端引用，用于传输与 moderationsPath 字段。 */
	private final OpenAiCompatClient client;

	/**
	 * @param client 外层 OpenAI 兼容客户端
	 */
	ModerationCompatStrategy(OpenAiCompatClient client) {
		this.client = client;
	}

	/** 内容审核入口。 */
	ModerationResponse moderate(ModerationRequest request) {
		JsonObject body = Json.object();
		body.put("model", request.model());
		body.put("input", request.input());
		for (Map.Entry<String, Object> e : request.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		var result = this.client.transportPostRaw(this.client.moderationsPath, body);
		JsonObject resp = result.json();
		List<ModerationResult> results = new ArrayList<>();
		JsonArray arr = resp.has("results") ? resp.getJsonArray("results") : null;
		if (arr != null) {
			for (int i = 0; i < arr.size(); i++) {
				JsonObject r = arr.getJsonObject(i);
				Map<String, Double> scores = new LinkedHashMap<>();
				Set<String> categories = new LinkedHashSet<>();
				if (r.has("category_scores")) {
					JsonObject cs = r.getJsonObject("category_scores");
					for (String key : cs.keySet()) {
						scores.put(key, cs.get(key).getAsDouble());
					}
				}
				if (r.has("categories")) {
					JsonObject c = r.getJsonObject("categories");
					for (String key : c.keySet()) {
						if (c.get(key).getAsBoolean()) {
							categories.add(key);
						}
					}
				}
				results.add(ModerationResult.of(
					r.has("flagged") && r.get("flagged").getAsBoolean(), scores, categories));
			}
		}
		return ModerationResponse.of(resp.optString("id", null), resp.optString("model", null),
			results, result.rawBody());
	}
}
