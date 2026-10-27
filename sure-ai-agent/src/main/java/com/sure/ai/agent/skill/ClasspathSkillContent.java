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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * classpath/jar 型技能内容：通过 ClassLoader 按需读取 {@code <resourceDir>/SKILL.md}
 * 与相对资源（UTF-8），支持展开目录与 jar 内条目两种形态。
 *
 * @author sureai
 * @since 2.9.0
 */
final class ClasspathSkillContent implements SkillContent {

	/** 技能资源前缀（无首尾斜杠），如 {@code skills/pdf-processing}。 */
	private final String resourceDir;

	/** 资源加载器。 */
	private final ClassLoader loader;

	/**
	 * 构造。
	 *
	 * @param resourceDir 技能资源前缀（无首尾斜杠）
	 * @param loader      类加载器
	 */
	ClasspathSkillContent(String resourceDir, ClassLoader loader) {
		this.resourceDir = resourceDir;
		this.loader = loader;
	}

	@Override
	public String readBody() {
		String text = readStream(this.resourceDir + "/SKILL.md");
		return SkillFrontmatterParser.parse(text).body();
	}

	@Override
	public String readResource(String relativePath) {
		String safe = SkillPaths.safeRelative(relativePath);
		return readStream(this.resourceDir + "/" + safe);
	}

	@Override
	public String origin() {
		return "classpath:" + this.resourceDir;
	}

	/**
	 * 以 UTF-8 读取 classpath 资源流。
	 */
	private String readStream(String name) {
		try (InputStream in = this.loader.getResourceAsStream(name)) {
			if (in == null) {
				throw new SkillResourceException("资源不存在: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new SkillResourceException("读取 classpath 资源失败: " + name, e);
		}
	}
}
