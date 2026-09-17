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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 对象，保留插入顺序。
 *
 * <p>直接继承 {@link LinkedHashMap}，键为 String，值为 {@link JsonElement}。提供类型化便捷读取方法。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class JsonObject extends JsonElement {

	private final LinkedHashMap<String, JsonElement> map = new LinkedHashMap<>();

	/** 构造空对象。 */
	public JsonObject() {
	}

	@Override
	public JsonObject getAsJsonObject() {
		return this;
	}

	/**
	 * 判断键是否存在且非 null。
	 *
	 * @param key 键名
	 * @return 存在且非 null 返回 true
	 */
	public boolean has(String key) {
		JsonElement el = this.map.get(key);
		return el != null && !el.isNull();
	}

	/**
	 * 放入原始 JSON 值。
	 *
	 * @param key   键名
	 * @param value 值
	 * @return 旧值
	 */
	public JsonElement put(String key, JsonElement value) {
		return this.map.put(key, value);
	}

	/**
	 * 放入字符串值。
	 *
	 * @param key   键名
	 * @param value 字符串值
	 */
	public void put(String key, String value) {
		this.map.put(key, new JsonPrimitive(value));
	}

	/**
	 * 放入数字值。
	 *
	 * @param key   键名
	 * @param value 数字值
	 */
	public void put(String key, Number value) {
		this.map.put(key, new JsonPrimitive(value));
	}

	/**
	 * 放入布尔值。
	 *
	 * @param key   键名
	 * @param value 布尔值
	 */
	public void put(String key, Boolean value) {
		this.map.put(key, new JsonPrimitive(value));
	}

	/**
	 * 放入任意 Java 值，自动转换为 JsonElement。
	 *
	 * @param key   键名
	 * @param value 任意值（String/Number/Boolean/Map/List/JsonElement/null）
	 */
	public void set(String key, Object value) {
		this.map.put(key, Json.toElement(value));
	}

	/**
	 * 移除键。
	 *
	 * @param key 键名
	 * @return 被移除的值
	 */
	public JsonElement remove(String key) {
		return this.map.remove(key);
	}

	/**
	 * 读取键对应的元素。
	 *
	 * @param key 键名
	 * @return 元素，不存在返回 null
	 */
	public JsonElement get(String key) {
		return this.map.get(key);
	}

	/**
	 * 读取字符串值。
	 *
	 * @param key 键名
	 * @return 字符串值
	 */
	public String getString(String key) {
		return this.map.get(key).getAsString();
	}

	/**
	 * 读取字符串值，不存在时返回默认值。
	 *
	 * @param key 键名
	 * @param def 默认值
	 * @return 字符串值或默认值
	 */
	public String optString(String key, String def) {
		JsonElement el = this.map.get(key);
		if (el == null || el.isNull()) {
			return def;
		}
		return el.getAsString();
	}

	/**
	 * 读取 int 值。
	 *
	 * @param key 键名
	 * @return int 值
	 */
	public int getInt(String key) {
		return this.map.get(key).getAsInt();
	}

	/**
	 * 读取 double 值。
	 *
	 * @param key 键名
	 * @return double 值
	 */
	public double getDouble(String key) {
		return this.map.get(key).getAsDouble();
	}

	/**
	 * 读取 boolean 值。
	 *
	 * @param key 键名
	 * @return boolean 值
	 */
	public boolean getBoolean(String key) {
		return this.map.get(key).getAsBoolean();
	}

	/**
	 * 读取嵌套对象。
	 *
	 * @param key 键名
	 * @return 对象
	 */
	public JsonObject getJsonObject(String key) {
		return this.map.get(key).getAsJsonObject();
	}

	/**
	 * 读取嵌套数组。
	 *
	 * @param key 键名
	 * @return 数组
	 */
	public JsonArray getJsonArray(String key) {
		return this.map.get(key).getAsJsonArray();
	}

	/**
	 * 键集合视图。
	 *
	 * @return 键集合
	 */
	public Iterable<String> keySet() {
		return this.map.keySet();
	}

	/**
	 * 键值集合视图。
	 *
	 * @return entry 集合
	 */
	public Iterable<Map.Entry<String, JsonElement>> entries() {
		return this.map.entrySet();
	}

	/**
	 * 元素个数。
	 *
	 * @return 个数
	 */
	public int size() {
		return this.map.size();
	}

	@Override
	public String optString(String key) {
		JsonElement el = this.map.get(key);
		if (el == null || !el.isString()) {
			return null;
		}
		return el.getAsString();
	}

	@Override
	public int optInt(String key, int defaultValue) {
		JsonElement el = this.map.get(key);
		if (el == null || !el.isNumber()) {
			return defaultValue;
		}
		return el.getAsInt();
	}

	@Override
	public long optLong(String key, long defaultValue) {
		JsonElement el = this.map.get(key);
		if (el == null || !el.isNumber()) {
			return defaultValue;
		}
		return el.getAsLong();
	}

	/**
	 * 取 double 值，缺失或非数字时返回默认值。
	 *
	 * @param key          键
	 * @param defaultValue 默认值
	 * @return double 值
	 */
	public double optDouble(String key, double defaultValue) {
		JsonElement el = this.map.get(key);
		if (el == null || !el.isNumber()) {
			return defaultValue;
		}
		return el.getAsDouble();
	}

	@Override
	public String toString() {
		return JsonWriter.stringify(this);
	}
}
