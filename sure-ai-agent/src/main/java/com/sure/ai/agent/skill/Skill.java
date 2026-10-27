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

import com.sure.tool.lang.Assert;

/**
 * 技能句柄：Level 1 元数据（{@link #manifest()}）随加载就绪并可进入系统提示；
 * Level 2 正文 {@link #loadBody()} 与 Level 3 资源 {@link #loadResource(String)}
 * 按需读取（渐进式加载），加载期不一次性读入。
 *
 * <p>本批<b>不执行</b>技能内 scripts/ 脚本，仅暴露指令与文本资源。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class Skill {

	/** 技能元数据。 */
	private final SkillManifest manifest;

	/** 内容来源（本地目录 / classpath）。 */
	private final SkillContent content;

	/**
	 * 构造（包内可见，由 {@link SkillLoader} 产出）。
	 *
	 * @param manifest 规范化元数据
	 * @param content  内容来源
	 */
	Skill(SkillManifest manifest, SkillContent content) {
		Assert.notNull(manifest, "manifest must not be null");
		Assert.notNull(content, "content must not be null");
		this.manifest = manifest;
		this.content = content;
	}

	/**
	 * 技能元数据（Level 1，加载即就绪）。
	 *
	 * @return 元数据
	 */
	public SkillManifest manifest() {
		return this.manifest;
	}

	/**
	 * 技能名（便捷访问，等同 {@code manifest().name()}）。
	 *
	 * @return 技能名
	 */
	public String name() {
		return this.manifest.name();
	}

	/**
	 * 按需读取 SKILL.md 正文指令（Level 2，触发时才进入上下文）。
	 *
	 * @return 正文 Markdown
	 * @throws SkillResourceException 读取失败或正文缺失
	 */
	public String loadBody() {
		return this.content.readBody();
	}

	/**
	 * 按需读取技能内相对资源（Level 3，如 references/guide.md）。
	 *
	 * @param relativePath 技能根下正斜杠相对路径
	 * @return 资源文本
	 * @throws SkillResourceException 路径越界、资源不存在或读取失败
	 */
	public String loadResource(String relativePath) {
		return this.content.readResource(relativePath);
	}

	/**
	 * 来源描述（本地目录路径或 {@code classpath:前缀}），仅用于日志/调试。
	 *
	 * @return 来源标识
	 */
	public String origin() {
		return this.content.origin();
	}
}
