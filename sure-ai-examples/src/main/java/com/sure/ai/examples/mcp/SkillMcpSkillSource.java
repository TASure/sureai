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
package com.sure.ai.examples.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.agent.skill.Skill;
import com.sure.ai.agent.skill.SkillLoader;
import com.sure.ai.agent.skill.SkillManifest;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.server.McpServer;
import com.sure.ai.mcp.server.skill.McpSkillInfo;
import com.sure.ai.mcp.server.skill.McpSkillResource;
import com.sure.ai.mcp.server.skill.McpSkillSource;

/**
 * Agent Skills 桥接器：把 {@link SkillLoader} 加载的 {@link Skill} 列表适配为
 * Skills over MCP 扩展（{@code io.modelcontextprotocol/skills}）的技能来源。
 *
 * <p>每个技能固定暴露 {@code SKILL.md} 正文资源；构造时额外声明的引用资源
 * （如 {@code references/xxx.md}）一并列入清单并计算 {@code sha256} 摘要。
 * URI 统一采用 {@code skill://<name>/<path>} 形态。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class SkillMcpSkillSource implements McpSkillSource {

	/** 技能名 -> 技能。 */
	private final Map<String, Skill> skills = new LinkedHashMap<>();

	/** 技能名 -> 额外资源相对路径（SKILL.md 之外的 L3 资源）。 */
	private final Map<String, List<String>> extraResources = new LinkedHashMap<>();

	/**
	 * 构造技能来源桥接器。
	 *
	 * @param skills 已加载的技能列表
	 */
	public SkillMcpSkillSource(List<Skill> skills) {
		for (Skill skill : skills) {
			this.skills.put(skill.name(), skill);
			this.extraResources.put(skill.name(), new ArrayList<>());
		}
	}

	/**
	 * 为某技能追加一个清单内资源（相对路径，如 {@code references/welcome.md}）。
	 *
	 * @param skillName    技能名
	 * @param relativePath 资源相对路径
	 * @return this
	 */
	public SkillMcpSkillSource withResource(String skillName, String relativePath) {
		List<String> list = this.extraResources.get(skillName);
		if (list != null) {
			list.add(relativePath);
		}
		return this;
	}

	@Override
	public List<McpSkillInfo> listSkills() {
		List<McpSkillInfo> result = new ArrayList<>();
		for (Skill skill : this.skills.values()) {
			result.add(toInfo(skill));
		}
		return result;
	}

	@Override
	public McpSkillInfo getSkill(String uri) {
		String name = skillName(uri);
		Skill skill = name == null ? null : this.skills.get(name);
		return skill == null ? null : toInfo(skill);
	}

	@Override
	public String readResource(String uri) {
		String name = skillName(uri);
		Skill skill = name == null ? null : this.skills.get(name);
		if (skill == null) {
			return null;
		}
		String relative = relativePath(uri, name);
		if (relative == null || relative.isBlank() || "SKILL.md".equals(relative)) {
			return skill.loadBody();
		}
		try {
			return skill.loadResource(relative);
		} catch (RuntimeException ex) {
			// 资源不存在：协议层映射为 -32602
			return null;
		}
	}

	/**
	 * 构造技能清单条目（含 SKILL.md 与额外资源摘要）。
	 *
	 * @param skill 技能
	 * @return MCP 技能信息
	 */
	private McpSkillInfo toInfo(Skill skill) {
		List<McpSkillResource> resources = new ArrayList<>();
		String body = skill.loadBody();
		resources.add(toResource(skill.name(), "SKILL.md", body));
		for (String extra : this.extraResources.getOrDefault(skill.name(), List.of())) {
			String content;
			try {
				content = skill.loadResource(extra);
			} catch (RuntimeException ex) {
				content = null;
			}
			if (content != null) {
				resources.add(toResource(skill.name(), extra, content));
			}
		}
		return McpSkillInfo.of(McpServer.SKILL_URI_SCHEME + skill.name(),
			toFrontmatter(skill.manifest()), resources);
	}

	/**
	 * 清单 frontmatter 转 JSON 对象。
	 *
	 * @param manifest 技能清单
	 * @return frontmatter JSON
	 */
	private static JsonObject toFrontmatter(SkillManifest manifest) {
		JsonObject fm = Json.object();
		fm.put("name", manifest.name());
		fm.put("description", manifest.description());
		if (manifest.license() != null) {
			fm.put("license", manifest.license());
		}
		if (manifest.compatibility() != null) {
			fm.put("compatibility", manifest.compatibility());
		}
		return fm;
	}

	/**
	 * 构造资源描述（含 sha256 摘要与字节数）。
	 *
	 * @param skillName 技能名
	 * @param relative  资源相对路径
	 * @param content   文本内容
	 * @return 资源描述
	 */
	private static McpSkillResource toResource(String skillName, String relative, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		return new McpSkillResource(McpServer.SKILL_URI_SCHEME + skillName + "/" + relative,
			"sha256:" + sha256Hex(bytes), bytes.length);
	}

	/**
	 * 计算字节数组的 sha256 十六进制摘要。
	 *
	 * @param bytes 字节
	 * @return 十六进制摘要
	 */
	private static String sha256Hex(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(bytes);
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException ex) {
			// SHA-256 为 JDK 必备算法，理论不可达
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * 从 {@code skill://<name>[/path]} 提取技能名。
	 *
	 * @param uri 资源 URI
	 * @return 技能名；格式不符返回 null
	 */
	private static String skillName(String uri) {
		if (uri == null || !uri.startsWith(McpServer.SKILL_URI_SCHEME)) {
			return null;
		}
		String rest = uri.substring(McpServer.SKILL_URI_SCHEME.length());
		int slash = rest.indexOf('/');
		return slash < 0 ? rest : rest.substring(0, slash);
	}

	/**
	 * 从 URI 提取技能内相对路径。
	 *
	 * @param uri       资源 URI
	 * @param skillName 技能名
	 * @return 相对路径；无路径返回 null
	 */
	private static String relativePath(String uri, String skillName) {
		String prefix = McpServer.SKILL_URI_SCHEME + skillName + "/";
		return uri.startsWith(prefix) ? uri.substring(prefix.length()) : null;
	}
}
