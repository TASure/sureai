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

import java.util.Map;

/**
 * JSON 序列化器。
 *
 * <p>控制字符转义为换行/回车/制表/引号/反斜杠与四位十六进制 Unicode 转义；非 ASCII 字符按 UTF-8 原样输出，不转义中文。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class JsonWriter {

	/** 私有构造器。 */
	private JsonWriter() {
		throw new AssertionError("No instances");
	}

	/**
	 * 序列化 JsonElement。
	 *
	 * @param element 元素
	 * @return JSON 文本
	 */
	public static String stringify(JsonElement element) {
		StringBuilder sb = new StringBuilder();
		write(element, sb);
		return sb.toString();
	}

	/** 递归写入。 */
	private static void write(JsonElement el, StringBuilder sb) {
		if (el == null || el.isNull()) {
			sb.append("null");
			return;
		}
		if (el instanceof JsonPrimitive p) {
			writePrimitive(p, sb);
			return;
		}
		if (el instanceof JsonObject obj) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<String, JsonElement> e : obj.entries()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				writeString(e.getKey(), sb);
				sb.append(':');
				write(e.getValue(), sb);
			}
			sb.append('}');
			return;
		}
		if (el instanceof JsonArray arr) {
			sb.append('[');
			boolean first = true;
			for (JsonElement e : arr) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				write(e, sb);
			}
			sb.append(']');
			return;
		}
		sb.append("null");
	}

	/** 写入原始值。 */
	private static void writePrimitive(JsonPrimitive p, StringBuilder sb) {
		Object v = p.value;
		if (v == null) {
			sb.append("null");
			return;
		}
		if (v instanceof String str) {
			writeString(str, sb);
			return;
		}
		if (v instanceof Number) {
			if (p.rawNumber != null) {
				sb.append(p.rawNumber);
			} else {
				sb.append(v.toString());
			}
			return;
		}
		sb.append(v.toString());
	}

	/** 写入带引号转义的字符串。 */
	private static void writeString(String str, StringBuilder sb) {
		sb.append('"');
		for (int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				case '\b':
					sb.append("\\b");
					break;
				case '\f':
					sb.append("\\f");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
	}
}
