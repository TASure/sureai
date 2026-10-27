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

/**
 * 技能内容来源抽象：屏蔽「本地目录文件」与「classpath/jar 资源」两种存储，
 * 供 {@link Skill} 按需读取正文与资源（渐进式加载 Level 2 / Level 3）。
 *
 * @author sureai
 * @since 2.9.0
 */
interface SkillContent {

	/**
	 * 按需读取 SKILL.md 正文（frontmatter 之后的 Markdown 指令）。
	 *
	 * @return 正文文本
	 * @throws SkillResourceException 读取失败或正文缺失
	 */
	String readBody();

	/**
	 * 按需读取技能内相对资源文本（references/、assets/ 等，UTF-8）。
	 *
	 * @param relativePath 技能根下的相对正斜杠路径
	 * @return 资源文本
	 * @throws SkillResourceException 路径越界、资源不存在或读取失败
	 */
	String readResource(String relativePath);

	/**
	 * 来源描述（本地目录路径或 classpath 资源前缀），仅用于日志/调试。
	 *
	 * @return 来源标识
	 */
	String origin();
}
