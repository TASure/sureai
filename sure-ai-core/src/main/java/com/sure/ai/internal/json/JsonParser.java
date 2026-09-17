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
 * 自研零依赖 JSON 解析器。
 *
 * <p>支持完整 JSON 语法：字符串转义（含四位十六进制 Unicode 转义）、整数/浮点数/科学计数法、
 * 嵌套对象与数组、null/true/false、尾随空白。语法错误抛出 {@link AiException}。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class JsonParser {

	/** 输入文本 */
	private final String s;

	/** 当前下标 */
	private int pos;

	/**
	 * 私有构造器。
	 *
	 * @param s 输入文本
	 */
	private JsonParser(String s) {
		this.s = s;
	}

	/**
	 * 解析 JSON 文本。
	 *
	 * @param json JSON 文本
	 * @return 解析结果
	 * @throws AiException 语法错误时抛出
	 */
	public static JsonElement parse(String json) {
		if (json == null) {
			throw new AiException("Invalid JSON: input is null");
		}
		JsonParser p = new JsonParser(json);
		p.skipWs();
		JsonElement el = p.parseValue();
		p.skipWs();
		if (p.pos != p.s.length()) {
			throw new AiException("Invalid JSON: trailing content at " + p.pos);
		}
		return el;
	}

	/** 解析任意值。 */
	private JsonElement parseValue() {
		if (this.pos >= this.s.length()) {
			throw err("unexpected end of input");
		}
		char c = this.s.charAt(this.pos);
		switch (c) {
			case '{':
				return parseObject();
			case '[':
				return parseArray();
			case '"':
				return new JsonPrimitive(parseString());
			case 't':
				expectLiteral("true");
				return new JsonPrimitive(Boolean.TRUE);
			case 'f':
				expectLiteral("false");
				return new JsonPrimitive(Boolean.FALSE);
			case 'n':
				expectLiteral("null");
				return JsonPrimitive.jsonNull();
			default:
				return parseNumber();
		}
	}

	/** 解析对象。 */
	private JsonObject parseObject() {
		JsonObject obj = new JsonObject();
		this.pos++; // {
		skipWs();
		if (peek() == '}') {
			this.pos++;
			return obj;
		}
		while (true) {
			skipWs();
			if (peek() != '"') {
				throw err("expected string key at " + this.pos);
			}
			String key = parseString();
			skipWs();
			if (peek() != ':') {
				throw err("expected ':' at " + this.pos);
			}
			this.pos++;
			skipWs();
			JsonElement val = parseValue();
			obj.put(key, val);
			skipWs();
			char c = peek();
			if (c == ',') {
				this.pos++;
			} else if (c == '}') {
				this.pos++;
				return obj;
			} else {
				throw err("expected ',' or '}' at " + this.pos);
			}
		}
	}

	/** 解析数组。 */
	private JsonArray parseArray() {
		JsonArray arr = new JsonArray();
		this.pos++; // [
		skipWs();
		if (peek() == ']') {
			this.pos++;
			return arr;
		}
		while (true) {
			skipWs();
			arr.add(parseValue());
			skipWs();
			char c = peek();
			if (c == ',') {
				this.pos++;
			} else if (c == ']') {
				this.pos++;
				return arr;
			} else {
				throw err("expected ',' or ']' at " + this.pos);
			}
		}
	}

	/** 解析字符串（含前导引号）。 */
	private String parseString() {
		this.pos++; // opening quote
		StringBuilder sb = new StringBuilder();
		while (true) {
			if (this.pos >= this.s.length()) {
				throw err("unterminated string");
			}
			char c = this.s.charAt(this.pos);
			if (c == '"') {
				this.pos++;
				return sb.toString();
			}
			if (c == '\\') {
				this.pos++;
				if (this.pos >= this.s.length()) {
					throw err("unterminated escape");
				}
				char esc = this.s.charAt(this.pos);
				this.pos++;
				switch (esc) {
					case '"':
						sb.append('"');
						break;
					case '\\':
						sb.append('\\');
						break;
					case '/':
						sb.append('/');
						break;
					case 'b':
						sb.append('\b');
						break;
					case 'f':
						sb.append('\f');
						break;
					case 'n':
						sb.append('\n');
						break;
					case 'r':
						sb.append('\r');
						break;
					case 't':
						sb.append('\t');
						break;
					case 'u':
						sb.append(parseUnicode());
						break;
					default:
						throw err("invalid escape '\\" + esc + "' at " + this.pos);
				}
			} else {
				if (c < 0x20) {
					throw err("unescaped control char at " + this.pos);
				}
				sb.append(c);
				this.pos++;
			}
		}
	}

	/** 解析四位十六进制 Unicode 转义。 */
	private char parseUnicode() {
		if (this.pos + 4 > this.s.length()) {
			throw err("bad unicode escape");
		}
		int code = 0;
		for (int i = 0; i < 4; i++) {
			char h = this.s.charAt(this.pos++);
			code <<= 4;
			if (h >= '0' && h <= '9') {
				code |= h - '0';
			} else if (h >= 'a' && h <= 'f') {
				code |= h - 'a' + 10;
			} else if (h >= 'A' && h <= 'F') {
				code |= h - 'A' + 10;
			} else {
				throw err("bad unicode hex at " + this.pos);
			}
		}
		return (char) code;
	}

	/** 解析数字。 */
	private JsonElement parseNumber() {
		int start = this.pos;
		if (peek() == '-') {
			this.pos++;
		}
		while (this.pos < this.s.length()) {
			char c = this.s.charAt(this.pos);
			if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
				this.pos++;
			} else {
				break;
			}
		}
		String token = this.s.substring(start, this.pos);
		if (token.isEmpty() || token.equals("-")) {
			throw err("invalid number at " + start);
		}
		return JsonPrimitive.numberToken(token);
	}

	/** 期望字面量。 */
	private void expectLiteral(String lit) {
		if (this.s.startsWith(lit, this.pos)) {
			this.pos += lit.length();
		} else {
			throw err("invalid literal at " + this.pos);
		}
	}

	/** 跳过空白。 */
	private void skipWs() {
		while (this.pos < this.s.length()) {
			char c = this.s.charAt(this.pos);
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				this.pos++;
			} else {
				break;
			}
		}
	}

	/** 窥视当前字符（越界返回 0）。 */
	private char peek() {
		if (this.pos >= this.s.length()) {
			return 0;
		}
		return this.s.charAt(this.pos);
	}

	/** 构造异常。 */
	private AiException err(String msg) {
		return new AiException("Invalid JSON: " + msg);
	}
}
