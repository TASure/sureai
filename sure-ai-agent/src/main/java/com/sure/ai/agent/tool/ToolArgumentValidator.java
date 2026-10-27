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
package com.sure.ai.agent.tool;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;

/**
 * 工具参数校验器：按 ToolFunction.parameters（JSON Schema 字符串）做轻量校验。
 *
 * <p><b>简化范围说明</b>：本实现仅校验 JSON Schema 的两个维度，不做完整 JSON Schema 校验：</p>
 * <ul>
 *   <li>{@code required}：字段必须存在且非 null；</li>
 *   <li>各 {@code properties[*].type}：仅做基础类型判定
 *       （string / integer / number / boolean / array / object）。</li>
 * </ul>
 * <p>刻意不实现 pattern / minimum / maximum / enum / minLength 等约束，
 * 这些由工具处理器自身在业务层处理，避免引入完整 JSON Schema 校验器。</p>
 *
 * <p>纯 JDK + core 自研 JSON 实现，无任何第三方依赖。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public final class ToolArgumentValidator {

	/**
	 * 校验结果。
	 *
	 * @param valid  是否通过
	 * @param errors 错误信息列表（可读，列出缺失字段与类型不匹配字段）
	 */
	public record ValidationResult(boolean valid, List<String> errors) {

		/** 紧凑构造器（防御性拷贝）。 */
		public ValidationResult {
			errors = errors == null ? List.of() : List.copyOf(errors);
		}

		/**
		 * 通过结果。
		 *
		 * @return 空错误的通过结果
		 */
		public static ValidationResult ok() {
			return new ValidationResult(true, List.of());
		}

		/**
		 * 失败结果。
		 *
		 * @param errors 错误列表
		 * @return 失败结果
		 */
		public static ValidationResult fail(List<String> errors) {
			return new ValidationResult(false, errors);
		}
	}

	/** 私有构造器（静态工具类）。 */
	public ToolArgumentValidator() {
	}

	/**
	 * 校验参数。
	 *
	 * @param function  工具函数定义（含 parameters JSON Schema）
	 * @param arguments 模型返回并已解析的参数对象
	 * @return 校验结果（valid=false 时 errors 列出问题）
	 */
	public ValidationResult validate(ToolFunction function, JsonObject arguments) {
		List<String> errors = new ArrayList<>();
		String schemaText = function.parameters();

		// 空 schema：不做任何校验，全部通过
		if (schemaText == null || schemaText.isBlank()) {
			return ValidationResult.ok();
		}

		JsonObject schema;
		try {
			JsonElement el = Json.parse(schemaText);
			if (el == null || !el.isObject()) {
				errors.add("parameters JSON Schema 不是合法的 JSON 对象");
				return ValidationResult.fail(errors);
			}
			schema = el.getAsJsonObject();
		} catch (RuntimeException e) {
			errors.add("parameters JSON Schema 解析失败: " + e.getMessage());
			return ValidationResult.fail(errors);
		}

		JsonObject props = schema.has("properties") && schema.get("properties").isObject()
				? schema.get("properties").getAsJsonObject() : null;

		// required 校验
		if (schema.has("required") && schema.get("required").isArray()) {
			JsonArray required = schema.get("required").getAsJsonArray();
			for (JsonElement req : required) {
				String field = req.getAsString();
				if (arguments == null || !arguments.has(field) || arguments.get(field).isNull()) {
					errors.add("缺少必填参数: " + field);
				}
			}
		}

		// 类型校验：只对出现在 arguments 中且 schema 声明了 type 的字段校验
		if (props != null && arguments != null) {
			for (String field : arguments.keySet()) {
				if (!props.has(field) || !props.get(field).isObject()) {
					continue;
				}
				JsonObject fieldSchema = props.get(field).getAsJsonObject();
				if (!fieldSchema.has("type")) {
					continue;
				}
				String type = fieldSchema.get("type").getAsString();
				JsonElement value = arguments.get(field);
				String mismatch = checkType(field, type, value);
				if (mismatch != null) {
					errors.add(mismatch);
				}
			}
		}

		return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
	}

	/**
	 * 单字段类型校验，返回 null 表示通过，否则返回错误描述。
	 */
	private String checkType(String field, String type, JsonElement value) {
		if (value == null || value.isNull()) {
			return null; // required 缺失已在上一步处理；非必填 null 放行
		}
		switch (type) {
			case "string":
				if (!value.isString()) {
					return "参数 " + field + " 应为 string，实际为 " + describe(value);
				}
				break;
			case "integer":
			case "number":
				if (!value.isNumber()) {
					return "参数 " + field + " 应为 " + type + "，实际为 " + describe(value);
				}
				break;
			case "boolean":
				if (!value.isBoolean()) {
					return "参数 " + field + " 应为 boolean，实际为 " + describe(value);
				}
				break;
			case "array":
				if (!value.isArray()) {
					return "参数 " + field + " 应为 array，实际为 " + describe(value);
				}
				break;
			case "object":
				if (!value.isObject()) {
					return "参数 " + field + " 应为 object，实际为 " + describe(value);
				}
				break;
			default:
				// 未知类型不报错，交给业务层处理
				break;
		}
		return null;
	}

	/**
	 * 描述实际值类型，用于错误信息。
	 */
	private String describe(JsonElement value) {
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
}
