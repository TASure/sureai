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

package com.sure.ai.internal.json;

import java.util.List;
import java.util.Map;

/**
 * JSON 门面工具类：解析、序列化与工厂方法。
 *
 * <p>零依赖，所有方法静态。接受普通 Java 类型自动转换为 JsonElement。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class Json {

	/** 私有构造器。 */
	private Json() {
		throw new AssertionError("No instances");
	}

	/**
	 * 解析 JSON 文本为 JsonElement。
	 *
	 * @param json JSON 文本
	 * @return 解析结果
	 */
	public static JsonElement parse(String json) {
		return JsonParser.parse(json);
	}

	/**
	 * 序列化为 JSON 文本。
	 *
	 * @param element 元素
	 * @return JSON 文本
	 */
	public static String stringify(JsonElement element) {
		return JsonWriter.stringify(element);
	}

	/**
	 * 将任意对象序列化为 JSON 文本。
	 *
	 * @param obj JsonElement 或 Map/List/String/Number/Boolean/null
	 * @return JSON 文本
	 */
	public static String stringify(Object obj) {
		return JsonWriter.stringify(toElement(obj));
	}

	/**
	 * 创建空对象。
	 *
	 * @return 空对象
	 */
	public static JsonObject object() {
		return new JsonObject();
	}

	/**
	 * 创建空数组。
	 *
	 * @return 空数组
	 */
	public static JsonArray array() {
		return new JsonArray();
	}

	/**
	 * 将任意 Java 值转换为 JsonElement。
	 *
	 * @param obj 任意值
	 * @return JsonElement
	 */
	public static JsonElement toElement(Object obj) {
		if (obj == null) {
			return JsonPrimitive.jsonNull();
		}
		if (obj instanceof JsonElement el) {
			return el;
		}
		if (obj instanceof String s) {
			return new JsonPrimitive(s);
		}
		if (obj instanceof Number n) {
			return new JsonPrimitive(n);
		}
		if (obj instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (obj instanceof Map<?, ?> map) {
			JsonObject jo = new JsonObject();
			for (Map.Entry<?, ?> e : map.entrySet()) {
				jo.put(String.valueOf(e.getKey()), toElement(e.getValue()));
			}
			return jo;
		}
		if (obj instanceof List<?> list) {
			JsonArray ja = new JsonArray();
			for (Object o : list) {
				ja.add(toElement(o));
			}
			return ja;
		}
		if (obj instanceof Object[] arr) {
			JsonArray ja = new JsonArray();
			for (Object o : arr) {
				ja.add(toElement(o));
			}
			return ja;
		}
		return new JsonPrimitive(String.valueOf(obj));
	}
}
