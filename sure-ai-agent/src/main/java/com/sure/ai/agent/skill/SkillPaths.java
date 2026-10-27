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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 技能资源路径安全规范化：拒绝绝对路径、反斜杠、NUL 字节与 {@code ..} 穿越，
 * 统一输出正斜杠相对路径。
 *
 * @author sureai
 * @since 2.9.0
 */
final class SkillPaths {

	/** 私有构造（静态工具类）。 */
	private SkillPaths() {
	}

	/**
	 * 校验并规范化相对路径。
	 *
	 * @param relativePath 原始相对路径
	 * @return 规范化后的正斜杠相对路径（无 {@code .} / {@code ..} 段）
	 * @throws SkillResourceException 空路径、绝对路径、反斜杠、NUL 或 {@code ..} 穿越
	 */
	static String safeRelative(String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			throw new SkillResourceException("资源路径不能为空");
		}
		if (relativePath.indexOf('\0') >= 0) {
			throw new SkillResourceException("资源路径包含非法 NUL 字节: " + relativePath);
		}
		if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
			throw new SkillResourceException("禁止绝对路径资源: " + relativePath);
		}
		if (relativePath.indexOf('\\') >= 0) {
			throw new SkillResourceException("禁止反斜杠路径（规范要求正斜杠）: " + relativePath);
		}
		Deque<String> stack = new ArrayDeque<>();
		for (String part : relativePath.split("/")) {
			if (part.isEmpty() || ".".equals(part)) {
				continue;
			}
			if ("..".equals(part)) {
				throw new SkillResourceException("禁止路径穿越（..）: " + relativePath);
			}
			stack.add(part);
		}
		if (stack.isEmpty()) {
			throw new SkillResourceException("资源路径不能为空: " + relativePath);
		}
		return String.join("/", stack);
	}
}
