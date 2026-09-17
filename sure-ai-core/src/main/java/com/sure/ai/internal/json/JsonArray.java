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

import java.util.ArrayList;

/**
 * JSON 数组。
 *
 * <p>继承 {@link ArrayList}，提供类型化便捷读取方法。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class JsonArray extends JsonElement implements Iterable<JsonElement> {

	private final ArrayList<JsonElement> list = new ArrayList<>();

	/** 构造空数组。 */
	public JsonArray() {
	}

	@Override
	public JsonArray getAsJsonArray() {
		return this;
	}

	/**
	 * 追加元素。
	 *
	 * @param el 元素
	 */
	public void add(JsonElement el) {
		this.list.add(el);
	}

	/**
	 * 追加字符串值。
	 *
	 * @param value 字符串
	 */
	public void add(String value) {
		this.list.add(new JsonPrimitive(value));
	}

	/**
	 * 追加数字值。
	 *
	 * @param value 数字
	 */
	public void add(Number value) {
		this.list.add(new JsonPrimitive(value));
	}

	/**
	 * 追加布尔值。
	 *
	 * @param value 布尔值
	 */
	public void add(Boolean value) {
		this.list.add(new JsonPrimitive(value));
	}

	/**
	 * 追加任意 Java 值，自动转换。
	 *
	 * @param value 任意值
	 */
	public void set(Object value) {
		this.list.add(Json.toElement(value));
	}

	/**
	 * 读取指定下标元素。
	 *
	 * @param i 下标
	 * @return 元素
	 */
	public JsonElement get(int i) {
		return this.list.get(i);
	}

	/**
	 * 读取字符串值。
	 *
	 * @param i 下标
	 * @return 字符串
	 */
	public String getString(int i) {
		return this.list.get(i).getAsString();
	}

	/**
	 * 读取对象。
	 *
	 * @param i 下标
	 * @return 对象
	 */
	public JsonObject getJsonObject(int i) {
		return this.list.get(i).getAsJsonObject();
	}

	/**
	 * 读取数组。
	 *
	 * @param i 下标
	 * @return 数组
	 */
	public JsonArray getJsonArray(int i) {
		return this.list.get(i).getAsJsonArray();
	}

	/**
	 * 读取 int 值。
	 *
	 * @param i 下标
	 * @return int
	 */
	public int getInt(int i) {
		return this.list.get(i).getAsInt();
	}

	/**
	 * 读取 double 值。
	 *
	 * @param i 下标
	 * @return double
	 */
	public double getDouble(int i) {
		return this.list.get(i).getAsDouble();
	}

	/**
	 * 读取 boolean 值。
	 *
	 * @param i 下标
	 * @return boolean
	 */
	public boolean getBoolean(int i) {
		return this.list.get(i).getAsBoolean();
	}

	/**
	 * 元素个数。
	 *
	 * @return 个数
	 */
	public int size() {
		return this.list.size();
	}

	/**
	 * 是否为空。
	 *
	 * @return 空返回 true
	 */
	public boolean isEmpty() {
		return this.list.isEmpty();
	}

	/**
	 * 元素迭代器。
	 *
	 * @return 迭代器
	 */
	public java.util.Iterator<JsonElement> iterator() {
		return this.list.iterator();
	}

	@Override
	public String toString() {
		return JsonWriter.stringify(this);
	}
}
