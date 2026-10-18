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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能元数据（SKILL.md YAML frontmatter 的规范化结果）。
 *
 * <p>字段依据 Anthropic Agent Skills 可移植规范（claude.ai / Skills API /
 * {@code package_skill.py} 允许集合）核实：</p>
 * <ul>
 *   <li>{@code name} 必填：≤64 字符，仅小写字母/数字/连字符，无 XML 尖括号，
 *       不得为保留词 {@code anthropic} / {@code claude}；</li>
 *   <li>{@code description} 必填：非空，≤1024 字符，无 XML 尖括号，
 *       需说明「做什么 + 何时使用」；</li>
 *   <li>{@code license} 可选：许可证声明字符串；</li>
 *   <li>{@code compatibility} 可选：环境/依赖要求，≤500 字符；</li>
 *   <li>{@code allowed-tools} 可选：允许的工具名集合（空格/逗号分隔标量或 YAML 列表）；</li>
 *   <li>{@code metadata} 可选：自由表单标量键值表，宿主不解释，仅供自有工具链读取。</li>
 * </ul>
 *
 * <p>规范<b>未定义</b> {@code version} 字段，本实现不提供；规范之外的未知标量键
 * 保留在 {@link #extraProperties()} 中以向前兼容，不参与任何行为。</p>
 *
 * @param name            技能名（命令/工具名）
 * @param description     技能描述（发现与触发依据）
 * @param license         许可证（可空）
 * @param compatibility   环境要求（可空）
 * @param allowedTools    允许工具名（不可变，可能为空）
 * @param metadata        自由元数据（不可变）
 * @param extraProperties 规范外未知标量键（不可变）
 * @author sureai
 * @since 2.9.0
 */
public record SkillManifest(String name, String description, String license,
		String compatibility, List<String> allowedTools,
		Map<String, String> metadata, Map<String, String> extraProperties) {

	/** name 最大长度（规范：64）。 */
	public static final int NAME_MAX_LENGTH = 64;

	/** description 最大长度（规范：1024）。 */
	public static final int DESCRIPTION_MAX_LENGTH = 1024;

	/** compatibility 最大长度（规范：500）。 */
	public static final int COMPATIBILITY_MAX_LENGTH = 500;

	/** 规范保留词（小写精确匹配）。 */
	private static final Set<String> RESERVED_NAMES = Set.of("anthropic", "claude");

	/** 已知规范字段（其余标量键进入 extraProperties）。 */
	private static final Set<String> KNOWN_KEYS = Set.of(
			"name", "description", "license", "compatibility", "allowed-tools", "metadata");

	/**
	 * 紧凑构造：防御性拷贝 + 规范校验。
	 */
	public SkillManifest {
		validateName(name);
		validateDescription(description);
		if (compatibility != null && compatibility.length() > COMPATIBILITY_MAX_LENGTH) {
			throw new SkillLoadException("compatibility 超过 " + COMPATIBILITY_MAX_LENGTH + " 字符");
		}
		allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		extraProperties = extraProperties == null ? Map.of() : Map.copyOf(extraProperties);
	}

	/**
	 * 从解析后的 frontmatter 构造并规范化 manifest。
	 *
	 * @param fm 解析中间产物
	 * @return 规范化 manifest
	 * @throws SkillLoadException 必填缺失、字段非法或类型不符
	 */
	static SkillManifest from(SkillFrontmatter fm) {
		Map<String, Object> fields = fm.fields();
		String name = scalar(fields, "name");
		String description = scalar(fields, "description");
		if (name == null || name.isBlank()) {
			throw new SkillLoadException("frontmatter 缺少必填字段: name");
		}
		if (description == null || description.isBlank()) {
			throw new SkillLoadException("frontmatter 缺少必填字段: description");
		}
		String license = scalar(fields, "license");
		String compatibility = scalar(fields, "compatibility");
		List<String> allowedTools = extractAllowedTools(fields.get("allowed-tools"));
		Map<String, String> metadata = extractMetadata(fields.get("metadata"));
		Map<String, String> extra = extractExtra(fields);
		return new SkillManifest(name.trim(), description.trim(), license,
				compatibility, allowedTools, metadata, extra);
	}

	/**
	 * 提取标量字段（缺失返回 null）。
	 */
	private static String scalar(Map<String, Object> fields, String key) {
		Object v = fields.get(key);
		return v == null ? null : String.valueOf(v);
	}

	/**
	 * allowed-tools：支持标量（逗号/空白分隔）与 YAML 列表两种形态。
	 */
	@SuppressWarnings("unchecked")
	private static List<String> extractAllowedTools(Object raw) {
		List<String> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				addTool(out, String.valueOf(item));
			}
		} else {
			for (String part : String.valueOf(raw).split("[,\\s]+")) {
				addTool(out, part);
			}
		}
		return out;
	}

	/**
	 * 去空白、去空、保序去重地加入工具名。
	 */
	private static void addTool(List<String> out, String candidate) {
		String t = candidate == null ? "" : candidate.trim();
		if (!t.isEmpty() && !out.contains(t)) {
			out.add(t);
		}
	}

	/**
	 * metadata：仅接受嵌套标量表。
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, String> extractMetadata(Object raw) {
		Map<String, String> out = new LinkedHashMap<>();
		if (raw instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> e : map.entrySet()) {
				out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
			}
		}
		return out;
	}

	/**
	 * 规范外未知标量键进入 extraProperties（列表/嵌套表形态的未知键丢弃并记录于文档）。
	 */
	private static Map<String, String> extractExtra(Map<String, Object> fields) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : fields.entrySet()) {
			if (KNOWN_KEYS.contains(e.getKey())) {
				continue;
			}
			if (e.getValue() instanceof String s) {
				out.put(e.getKey(), s);
			}
		}
		return out;
	}

	/**
	 * name 规范校验。
	 */
	private static void validateName(String name) {
		if (name == null || name.isBlank()) {
			throw new SkillLoadException("name 不能为空");
		}
		if (name.length() > NAME_MAX_LENGTH) {
			throw new SkillLoadException("name 超过 " + NAME_MAX_LENGTH + " 字符: " + name);
		}
		if (name.indexOf('<') >= 0 || name.indexOf('>') >= 0) {
			throw new SkillLoadException("name 不得包含 XML 尖括号: " + name);
		}
		if (!name.matches("[a-z0-9-]+")) {
			throw new SkillLoadException("name 仅允许小写字母、数字与连字符: " + name);
		}
		if (RESERVED_NAMES.contains(name)) {
			throw new SkillLoadException("name 为规范保留词，禁止使用: " + name);
		}
	}

	/**
	 * description 规范校验。
	 */
	private static void validateDescription(String description) {
		if (description == null || description.isBlank()) {
			throw new SkillLoadException("description 不能为空");
		}
		if (description.length() > DESCRIPTION_MAX_LENGTH) {
			throw new SkillLoadException("description 超过 " + DESCRIPTION_MAX_LENGTH + " 字符");
		}
		if (description.indexOf('<') >= 0 || description.indexOf('>') >= 0) {
			throw new SkillLoadException("description 不得包含 XML 尖括号");
		}
	}
}
