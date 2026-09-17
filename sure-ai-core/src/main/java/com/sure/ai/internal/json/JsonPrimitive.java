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
 * JSON 原始值：字符串、数字、布尔或 null。
 *
 * <p>数字同时保留原始词法片段，用于往返序列化时保持科学计数法、大整数等格式不变。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class JsonPrimitive extends JsonElement {

	/** 持有的值：String / Long / Double / Boolean / null */
	final Object value;

	/** 数字原始词法片段，非数字时为 null */
	final String rawNumber;

	/**
	 * 构造字符串原始值。
	 *
	 * @param value 字符串
	 */
	public JsonPrimitive(String value) {
		this.value = value;
		this.rawNumber = null;
	}

	/**
	 * 构造布尔原始值。
	 *
	 * @param value 布尔值
	 */
	public JsonPrimitive(Boolean value) {
		this.value = value;
		this.rawNumber = null;
	}

	/**
	 * 构造数字原始值（原始词法片段为 null，按数值默认格式输出）。
	 *
	 * @param value 数值
	 */
	public JsonPrimitive(Number value) {
		this.value = value;
		this.rawNumber = null;
	}

	/**
	 * 构造 JSON null。
	 *
	 * @return null 原始值
	 */
	public static JsonPrimitive jsonNull() {
		return new JsonPrimitive();
	}

	/** 私有构造 JSON null。 */
	private JsonPrimitive() {
		this.value = null;
		this.rawNumber = null;
	}

	/**
	 * 解析数字词法片段构造数字原始值。
	 *
	 * @param rawToken 数字词法片段
	 * @return 数字原始值
	 */
	static JsonPrimitive numberToken(String rawToken) {
		boolean floating = rawToken.indexOf('.') >= 0
			|| rawToken.indexOf('e') >= 0 || rawToken.indexOf('E') >= 0;
		if (!floating) {
			try {
				return new JsonPrimitive(Long.valueOf(Long.parseLong(rawToken)), rawToken);
			} catch (NumberFormatException ex) {
				// 超过 long 范围，落到 double
			}
		}
		return new JsonPrimitive(Double.valueOf(Double.parseDouble(rawToken)), rawToken);
	}

	/** 私有数字构造器。 */
	private JsonPrimitive(Number value, String rawToken) {
		this.value = value;
		this.rawNumber = rawToken;
	}

	@Override
	public String getAsString() {
		if (this.value == null) {
			return "null";
		}
		if (this.value instanceof String s) {
			return s;
		}
		if (this.value instanceof Number n) {
			return this.rawNumber != null ? this.rawNumber : n.toString();
		}
		return this.value.toString();
	}

	@Override
	public int getAsInt() {
		if (this.value instanceof Number n) {
			return n.intValue();
		}
		throw new AiException("Not a numeric JSON primitive: " + this.value);
	}

	@Override
	public long getAsLong() {
		if (this.value instanceof Number n) {
			return n.longValue();
		}
		throw new AiException("Not a numeric JSON primitive: " + this.value);
	}

	@Override
	public double getAsDouble() {
		if (this.value instanceof Number n) {
			return n.doubleValue();
		}
		throw new AiException("Not a numeric JSON primitive: " + this.value);
	}

	@Override
	public boolean getAsBoolean() {
		if (this.value instanceof Boolean b) {
			return b.booleanValue();
		}
		throw new AiException("Not a boolean JSON primitive: " + this.value);
	}

	@Override
	public String toString() {
		return JsonWriter.stringify(this);
	}
}
