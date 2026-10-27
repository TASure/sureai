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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地目录型技能内容：从技能目录按需读取 SKILL.md 正文与资源文件（UTF-8）。
 *
 * @author sureai
 * @since 2.9.0
 */
final class LocalSkillContent implements SkillContent {

	/** 技能根目录。 */
	private final Path skillDir;

	/**
	 * 构造。
	 *
	 * @param skillDir 技能目录（含 SKILL.md）
	 */
	LocalSkillContent(Path skillDir) {
		this.skillDir = skillDir;
	}

	@Override
	public String readBody() {
		String text = readUtf8(this.skillDir.resolve("SKILL.md"));
		return SkillFrontmatterParser.parse(text).body();
	}

	@Override
	public String readResource(String relativePath) {
		String safe = SkillPaths.safeRelative(relativePath);
		Path target = this.skillDir.resolve(safe).normalize();
		if (!Files.isRegularFile(target)) {
			throw new SkillResourceException("资源不存在: " + relativePath);
		}
		return readUtf8(target);
	}

	@Override
	public String origin() {
		return this.skillDir.toString();
	}

	/**
	 * 以 UTF-8 读取文本文件。
	 */
	private static String readUtf8(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new SkillResourceException("读取技能文件失败: " + file, e);
		}
	}
}
