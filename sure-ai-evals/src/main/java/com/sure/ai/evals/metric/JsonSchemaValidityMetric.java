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
package com.sure.ai.evals.metric;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.util.JsonSchemaGenerator;
import com.sure.tool.lang.Assert;

/**
 * JSON Schema 合法性指标：把实际输出当作 JSON，校验其与目标类的 Schema 是否一致。
 *
 * <p>目标类 Schema 在构造时由 core {@link JsonSchemaGenerator#generate(Class)} 一次性生成。
 * 校验为<b>结构化子集校验</b>：根必须是对象；遍历 {@code properties}——required 字段
 * （schema 的 {@code required} 数组）必须存在且 JSON 类型匹配（string/integer/number/
 * boolean/array/object）；可选字段缺失不扣分，但一旦出现类型必须匹配；枚举字段值须在
 * 枚举列表内。得分 = 通过检查数 / 应检数。</p>
 *
 * <p>容错：实际输出为空 / JSON 解析失败 / 根不是对象 → 判 0 分并在理由中说明；
 * 无 properties 的空 schema 判通过。本指标不要求用例带 {@code expected}，故不适用场景为
 * 实际输出为空。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class JsonSchemaValidityMetric implements EvalMetric {

	private final Class<?> targetType;
	private final JsonObject schema;
	private final String name;

	private JsonSchemaValidityMetric(Builder b) {
		this.targetType = b.targetType;
		this.schema = JsonSchemaGenerator.generate(b.targetType);
		this.name = b.name;
	}

	/**
	 * 创建 Builder。
	 *
	 * @param targetType 目标类（其 Schema 作为校验基准）
	 * @return Builder
	 */
	public static Builder builder(Class<?> targetType) {
		Assert.notNull(targetType, "targetType 不能为 null");
		return new Builder().targetType(targetType);
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context) {
		String text = outcome.actualText();
		if (text == null || text.isBlank()) {
			return MetricResult.fail("实际输出为空，无法按 JSON Schema 校验");
		}
		JsonElement root;
		try {
			root = Json.parse(text);
		} catch (RuntimeException e) {
			return MetricResult.fail("实际输出不是合法 JSON: " + e.getMessage());
		}
		if (!root.isObject()) {
			return MetricResult.fail("JSON 根不是对象，目标类型为 " + this.targetType.getSimpleName());
		}
		JsonObject obj = root.getAsJsonObject();
		JsonElement propsEl = this.schema.get("properties");
		if (propsEl == null || !propsEl.isObject() || propsEl.getAsJsonObject().size() == 0) {
			return MetricResult.pass("空 Schema，校验通过");
		}
		JsonObject props = propsEl.getAsJsonObject();
		List<String> required = requiredNames();
		int total = 0;
		int passed = 0;
		List<String> failures = new ArrayList<>();
		for (java.util.Map.Entry<String, JsonElement> entry : props.entries()) {
			String field = entry.getKey();
			JsonElement fieldSchema = entry.getValue();
			boolean isRequired = required.contains(field);
			JsonElement value = obj.get(field);
			if (value == null || value.isNull()) {
				if (isRequired) {
					total++;
					failures.add("缺必填字段 " + field);
				}
				continue;
			}
			total++;
			String problem = checkType(fieldSchema, value);
			if (problem == null) {
				passed++;
			} else {
				failures.add(problem);
			}
		}
		if (total == 0) {
			return MetricResult.pass("无可检字段");
		}
		double score = (double) passed / total;
		return new MetricResult(score, failures.isEmpty()
			? "Schema 校验通过 (" + passed + "/" + total + ")"
			: "Schema 校验失败: " + failures);
	}

	private List<String> requiredNames() {
		List<String> required = new ArrayList<>();
		JsonElement req = this.schema.get("required");
		if (req != null && req.isArray()) {
			JsonArray array = req.getAsJsonArray();
			for (JsonElement el : array) {
				if (el.isString()) {
					required.add(el.getAsString());
				}
			}
		}
		return required;
	}

	private static String checkType(JsonElement fieldSchema, JsonElement value) {
		if (!fieldSchema.isObject()) {
			return null;
		}
		JsonObject schema = fieldSchema.getAsJsonObject();
		JsonElement enumEl = schema.get("enum");
		if (enumEl != null && enumEl.isArray() && value.isString()) {
			boolean found = false;
			for (JsonElement option : enumEl.getAsJsonArray()) {
				if (option.isString() && option.getAsString().equals(value.getAsString())) {
					found = true;
					break;
				}
			}
			if (!found) {
				return "枚举值不匹配: " + value.getAsString();
			}
		}
		JsonElement typeEl = schema.get("type");
		if (typeEl == null || !typeEl.isString()) {
			return null;
		}
		String type = typeEl.getAsString();
		boolean ok = switch (type) {
			case "string" -> value.isString();
			case "integer", "number" -> value.isNumber();
			case "boolean" -> value.isBoolean();
			case "array" -> value.isArray();
			case "object" -> value.isObject();
			default -> true;
		};
		return ok ? null : "字段类型应为 " + type + "，实际为 " + describe(value);
	}

	private static String describe(JsonElement value) {
		if (value.isString()) {
			return "string";
		}
		if (value.isNumber()) {
			return "number";
		}
		if (value.isBoolean()) {
			return "boolean";
		}
		if (value.isArray()) {
			return "array";
		}
		if (value.isObject()) {
			return "object";
		}
		return "null";
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private Class<?> targetType;
		private String name = "json_schema";

		private Builder() {
		}

		private Builder targetType(Class<?> targetType) {
			this.targetType = targetType;
			return this;
		}

		/**
		 * 设置指标名（默认 {@code json_schema}）。
		 *
		 * @param name 指标名
		 * @return this
		 */
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public JsonSchemaValidityMetric build() {
			Assert.notNull(this.targetType, "targetType 不能为 null");
			return new JsonSchemaValidityMetric(this);
		}
	}
}
