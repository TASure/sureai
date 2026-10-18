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

package com.sure.ai.agent.skill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md 的 YAML frontmatter 手写子集解析器（零第三方依赖，不引入 snakeyaml）。
 *
 * <p><b>支持子集</b>（仅覆盖 Agent Skills 规范所需）：</p>
 * <ul>
 *   <li>首行 {@code ---} 开启、独立一行 {@code ---} 闭合；</li>
 *   <li>顶层 {@code key: value} 标量，支持双引号（{@code \"}、{@code \\} 转义）与单引号字面量；</li>
 *   <li>块列表：当前键下缩进的 {@code - item} 序列，解析为 {@code List<String>}；</li>
 *   <li>嵌套标量表：当前键下缩进的 {@code key: value}，解析为 {@code Map<String, String>}（用于 {@code metadata}）；</li>
 *   <li>空行与 {@code #} 整行注释忽略；重复顶层键、非法行抛 {@link SkillLoadException}。</li>
 * </ul>
 *
 * <p><b>明确不支持</b>：锚点/别名、多行折叠标量、流式数组 {@code [a, b]}、
 * 行内注释剥离、布尔/数字强类型转换（一律按字符串保留）。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class SkillFrontmatterParser {

	/** 私有构造（静态工具类）。 */
	private SkillFrontmatterParser() {
	}

	/**
	 * 解析 SKILL.md 全文。
	 *
	 * @param markdown SKILL.md 全文（UTF-8）
	 * @return frontmatter 字段 + 正文
	 * @throws SkillLoadException 首行缺少 {@code ---}、缺少闭合 {@code ---}、重复键或非法行
	 */
	public static SkillFrontmatter parse(String markdown) {
		if (markdown == null || markdown.isEmpty()) {
			throw new SkillLoadException("SKILL.md 内容为空");
		}
		String[] lines = markdown.split("\r?\n", -1);
		if (lines.length == 0 || !"---".equals(lines[0].trim())) {
			throw new SkillLoadException("缺少 YAML frontmatter：文件首行必须是 ---");
		}
		int close = -1;
		List<String> fmLines = new ArrayList<>();
		for (int i = 1; i < lines.length; i++) {
			if ("---".equals(lines[i].trim())) {
				close = i;
				break;
			}
			fmLines.add(lines[i]);
		}
		if (close < 0) {
			throw new SkillLoadException("YAML frontmatter 缺少闭合 ---");
		}
		String body = String.join("\n", Arrays.copyOfRange(lines, close + 1, lines.length));
		Map<String, Object> fields = parseFields(fmLines);
		return new SkillFrontmatter(fields, body);
	}

	/**
	 * 逐行解析 frontmatter 字段表。
	 */
	private static Map<String, Object> parseFields(List<String> lines) {
		Map<String, Object> result = new LinkedHashMap<>();
		String currentKey = null;
		List<String> currentList = null;
		Map<String, String> currentMap = null;
		for (String raw : lines) {
			String trimmed = raw.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			int indent = leadingSpaces(raw);
			if (indent == 0) {
				int colon = trimmed.indexOf(':');
				if (colon <= 0) {
					throw new SkillLoadException("非法的 frontmatter 行: " + trimmed);
				}
				String key = trimmed.substring(0, colon).trim();
				if (!key.matches("[A-Za-z0-9_-]+")) {
					throw new SkillLoadException("非法的 frontmatter 键名: " + key);
				}
				if (result.containsKey(key)) {
					throw new SkillLoadException("frontmatter 重复键: " + key);
				}
				currentList = null;
				currentMap = null;
				currentKey = key;
				String rest = trimmed.substring(colon + 1).trim();
				result.put(key, rest.isEmpty() ? null : unquote(rest));
			} else {
				if (currentKey == null) {
					throw new SkillLoadException("缩进内容缺少顶层键: " + trimmed);
				}
				if (trimmed.startsWith("- ")) {
					if (currentList == null) {
						currentList = new ArrayList<>();
						result.put(currentKey, currentList);
					}
					currentList.add(unquote(trimmed.substring(2).trim()));
				} else {
					int colon = trimmed.indexOf(':');
					if (colon <= 0) {
						throw new SkillLoadException("非法的缩进行: " + trimmed);
					}
					if (currentMap == null) {
						currentMap = new LinkedHashMap<>();
						result.put(currentKey, currentMap);
					}
					currentMap.put(trimmed.substring(0, colon).trim(),
							unquote(trimmed.substring(colon + 1).trim()));
				}
			}
		}
		// 空占位（键后无任何子行/标量）直接剔除
		result.values().removeIf(v -> v == null);
		return result;
	}

	/**
	 * 统计前导空格数（本子集仅以空格缩进为准，Tab 缩进按非法缩进行处理）。
	 */
	private static int leadingSpaces(String line) {
		int n = 0;
		while (n < line.length() && line.charAt(n) == ' ') {
			n++;
		}
		return n;
	}

	/**
	 * 剥离成对引号；双引号处理 {@code \"} 与 {@code \\} 转义，单引号原样保留。
	 */
	static String unquote(String value) {
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if (first == '"' && last == '"') {
				return unescapeDouble(value.substring(1, value.length() - 1));
			}
			if (first == '\'' && last == '\'') {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}

	/**
	 * 双引号标量的最小转义还原。
	 */
	private static String unescapeDouble(String inner) {
		StringBuilder sb = new StringBuilder(inner.length());
		boolean escaped = false;
		for (int i = 0; i < inner.length(); i++) {
			char c = inner.charAt(i);
			if (escaped) {
				sb.append(c == 'n' ? '\n' : c);
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
