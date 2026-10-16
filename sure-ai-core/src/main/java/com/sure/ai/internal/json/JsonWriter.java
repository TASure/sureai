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

	/** 小写十六进制数字表（控制字符 \\uXXXX 转义用，与原 String.format %04x 一致）。 */
	private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

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
		int len = str.length();
		int start = 0;
		int i = 0;
		while (i < len) {
			char c = str.charAt(i);
			// 需转义的字符：先把上一段"普通字符"整块追加，再写转义序列。
			String repl = switch (c) {
				case '"' -> "\\\"";
				case '\\' -> "\\\\";
				case '\n' -> "\\n";
				case '\r' -> "\\r";
				case '\t' -> "\\t";
				case '\b' -> "\\b";
				case '\f' -> "\\f";
				default -> null;
			};
			if (repl != null) {
				sb.append(str, start, i);
				sb.append(repl);
				start = i + 1;
			} else if (c < 0x20) {
				sb.append(str, start, i);
				appendUnicodeEscape(sb, c);
				start = i + 1;
			}
			i++;
		}
		// 收尾：剩余普通字符整块追加。
		sb.append(str, start, len);
		sb.append('"');
	}

	/** 手动拼接四位小写十六进制转义（替代 String.format，避免格式化器开销）。 */
	private static void appendUnicodeEscape(StringBuilder sb, int c) {
		sb.append("\\u");
		sb.append(HEX_DIGITS[(c >> 12) & 0xF]);
		sb.append(HEX_DIGITS[(c >> 8) & 0xF]);
		sb.append(HEX_DIGITS[(c >> 4) & 0xF]);
		sb.append(HEX_DIGITS[c & 0xF]);
	}
}
