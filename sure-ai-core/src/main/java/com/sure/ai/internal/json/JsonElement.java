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

import com.sure.ai.exception.AiException;

/**
 * JSON 元素抽象基类。
 *
 * <p>层次结构：{@link JsonPrimitive}（字符串/数字/布尔/null）、{@link JsonObject}、{@link JsonArray}。
 * 所有类型判断与类型化访问均通过本基类方法完成，调用方无需显式向下转型。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public abstract class JsonElement {

	/** 私有构造器，禁止外部继承。 */
	JsonElement() {
	}

	/**
	 * 是否为对象。
	 *
	 * @return 是对象返回 true
	 */
	public boolean isObject() {
		return this instanceof JsonObject;
	}

	/**
	 * 是否为数组。
	 *
	 * @return 是数组返回 true
	 */
	public boolean isArray() {
		return this instanceof JsonArray;
	}

	/**
	 * 是否为字符串原始值。
	 *
	 * @return 是字符串返回 true
	 */
	public boolean isString() {
		return this instanceof JsonPrimitive p && p.value instanceof String;
	}

	/**
	 * 是否为数字原始值。
	 *
	 * @return 是数字返回 true
	 */
	public boolean isNumber() {
		return this instanceof JsonPrimitive p && p.value instanceof Number;
	}

	/**
	 * 是否为布尔原始值。
	 *
	 * @return 是布尔返回 true
	 */
	public boolean isBoolean() {
		return this instanceof JsonPrimitive p && p.value instanceof Boolean;
	}

	/**
	 * 是否为 null。
	 *
	 * @return 是 null 返回 true
	 */
	public boolean isNull() {
		return this instanceof JsonPrimitive p && p.value == null;
	}

	/**
	 * 作为字符串返回。
	 *
	 * @return 字符串值
	 * @throws AiException 非原始值类型时抛出
	 */
	public String getAsString() {
		throw new AiException("Not a JSON primitive: " + this.getClass().getName());
	}

	/**
	 * 作为 int 返回。
	 *
	 * @return int 值
	 * @throws AiException 非数字时抛出
	 */
	public int getAsInt() {
		throw new AiException("Not a JSON number: " + this.getClass().getName());
	}

	/**
	 * 作为 long 返回。
	 *
	 * @return long 值
	 * @throws AiException 非数字时抛出
	 */
	public long getAsLong() {
		throw new AiException("Not a JSON number: " + this.getClass().getName());
	}

	/**
	 * 作为 double 返回。
	 *
	 * @return double 值
	 * @throws AiException 非数字时抛出
	 */
	public double getAsDouble() {
		throw new AiException("Not a JSON number: " + this.getClass().getName());
	}

	/**
	 * 作为 boolean 返回。
	 *
	 * @return boolean 值
	 * @throws AiException 非布尔时抛出
	 */
	public boolean getAsBoolean() {
		throw new AiException("Not a JSON boolean: " + this.getClass().getName());
	}

	/**
	 * 作为对象返回。
	 *
	 * @return JSON 对象
	 * @throws AiException 非对象时抛出
	 */
	public JsonObject getAsJsonObject() {
		throw new AiException("Not a JSON object: " + this.getClass().getName());
	}

	/**
	 * 作为数组返回。
	 *
	 * @return JSON 数组
	 * @throws AiException 非数组时抛出
	 */
	public JsonArray getAsJsonArray() {
		throw new AiException("Not a JSON array: " + this.getClass().getName());
	}

	/**
	 * 仅当本元素为对象时，读取指定键的字符串值；否则返回 null。
	 *
	 * @param key 键名
	 * @return 字符串值，不存在或非对象时返回 null
	 */
	public String optString(String key) {
		return null;
	}

	/**
	 * 仅当本元素为对象时，读取指定键的 int 值；否则返回默认值。
	 *
	 * @param key          键名
	 * @param defaultValue 默认值
	 * @return int 值，不存在或非对象时返回默认值
	 */
	public int optInt(String key, int defaultValue) {
		return defaultValue;
	}
}
