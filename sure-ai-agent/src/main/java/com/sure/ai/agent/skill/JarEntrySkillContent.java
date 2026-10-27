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

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * jar 条目型技能内容：加载期把 jar 条目字节读入内存映射，正文与资源文本按需解码，
 * 避免持有已关闭的 JarFile。
 *
 * @author sureai
 * @since 2.9.0
 */
final class JarEntrySkillContent implements SkillContent {

	/** jar 内全部条目名 → 字节内容（加载期一次性读入）。 */
	private final Map<String, byte[]> entries;

	/** 技能资源前缀（jar 内，无首尾斜杠），如 {@code embedded/jarskill}。 */
	private final String resourceDir;

	/**
	 * 构造。
	 *
	 * @param resourceDir 技能资源前缀（jar 内，无首尾斜杠）
	 * @param entries     jar 条目字节映射
	 */
	JarEntrySkillContent(String resourceDir, Map<String, byte[]> entries) {
		this.resourceDir = resourceDir;
		this.entries = entries;
	}

	@Override
	public String readBody() {
		String text = readEntry(this.resourceDir + "/SKILL.md");
		return SkillFrontmatterParser.parse(text).body();
	}

	@Override
	public String readResource(String relativePath) {
		String safe = SkillPaths.safeRelative(relativePath);
		return readEntry(this.resourceDir + "/" + safe);
	}

	@Override
	public String origin() {
		return "jar:" + this.resourceDir;
	}

	/**
	 * 读取内存中的 jar 条目文本（UTF-8），缺失抛 {@link SkillResourceException}。
	 */
	private String readEntry(String name) {
		byte[] bytes = this.entries.get(name);
		if (bytes == null) {
			throw new SkillResourceException("jar 资源不存在: " + name);
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
