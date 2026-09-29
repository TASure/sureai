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
package com.sure.ai.rag.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.rag.model.Document;

/**
 * 轨迹 JSON 序列化器：基于 sure-core 内置 JSON，零额外依赖。
 *
 * <p>将 {@link RagTrace}（含上下文文档列表与可选参考答案）序列化为紧凑 JSON，
 * 并支持反向解析，用于轨迹持久化与跨版本回放。文档仅映射 {@code id/text/metadata} 三字段。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class TraceSerializer {

	private TraceSerializer() {
		throw new AssertionError("No instances");
	}

	/**
	 * 将单条轨迹序列化为 JSON 文本。
	 *
	 * @param trace 轨迹
	 * @return JSON 文本
	 */
	public static String toJson(RagTrace trace) {
		return Json.stringify(traceToObject(trace));
	}

	/**
	 * 将轨迹列表序列化为 JSON 数组文本。
	 *
	 * @param traces 轨迹列表
	 * @return JSON 数组文本
	 */
	public static String toJsonList(List<RagTrace> traces) {
		JsonArray array = Json.array();
		for (RagTrace trace : traces) {
			array.add(traceToObject(trace));
		}
		return Json.stringify(array);
	}

	/**
	 * 从 JSON 文本解析单条轨迹。
	 *
	 * @param json JSON 文本
	 * @return 轨迹
	 * @throws IllegalArgumentException JSON 非法或缺必填字段时
	 */
	public static RagTrace fromJson(String json) {
		JsonElement el = Json.parse(json);
		return objectToTrace(el.getAsJsonObject());
	}

	/**
	 * 从 JSON 数组文本解析轨迹列表。
	 *
	 * @param json JSON 数组文本
	 * @return 轨迹列表
	 */
	public static List<RagTrace> fromJsonList(String json) {
		JsonArray array = Json.parse(json).getAsJsonArray();
		List<RagTrace> traces = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			traces.add(objectToTrace(array.getJsonObject(i)));
		}
		return List.copyOf(traces);
	}

	private static JsonObject traceToObject(RagTrace trace) {
		JsonObject obj = Json.object();
		obj.set("traceId", trace.traceId());
		obj.set("question", trace.question());
		obj.set("answer", trace.answer());
		if (trace.referenceAnswer() != null) {
			obj.set("referenceAnswer", trace.referenceAnswer());
		}
		JsonArray contexts = Json.array();
		for (Document doc : trace.contexts()) {
			JsonObject docObj = Json.object();
			docObj.set("id", doc.id());
			docObj.set("text", doc.text());
			docObj.set("metadata", doc.metadata());
			contexts.add(docObj);
		}
		obj.set("contexts", contexts);
		obj.set("metadata", trace.metadata());
		return obj;
	}

	private static RagTrace objectToTrace(JsonObject obj) {
		List<Document> contexts = new ArrayList<>();
		JsonArray ctxArray = obj.getJsonArray("contexts");
		if (ctxArray != null) {
			for (int i = 0; i < ctxArray.size(); i++) {
				JsonObject d = ctxArray.getJsonObject(i);
				Map<String, String> metadata = new LinkedHashMap<>();
				JsonObject md = d.getJsonObject("metadata");
				if (md != null) {
					for (String key : md.keySet()) {
						metadata.put(key, md.getString(key));
					}
				}
				contexts.add(new Document(d.getString("id"), d.getString("text"), metadata));
			}
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		JsonObject md = obj.getJsonObject("metadata");
		if (md != null) {
			for (String key : md.keySet()) {
				metadata.put(key, jsonToObject(md.get(key)));
			}
		}
		return RagTrace.builder()
				.traceId(obj.optString("traceId"))
				.question(obj.getString("question"))
				.answer(obj.getString("answer"))
				.contexts(contexts)
				.referenceAnswer(obj.optString("referenceAnswer", null))
				.metadata(metadata)
				.build();
	}

	private static Object jsonToObject(JsonElement el) {
		if (el == null || el.isNull()) {
			return null;
		}
		if (el.isBoolean()) {
			return el.getAsBoolean();
		}
		if (el.isNumber()) {
			return el.getAsDouble();
		}
		return el.getAsString();
	}
}
