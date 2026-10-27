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
 * 技能加载异常：SKILL.md 缺失 / frontmatter 非法 / 必填字段缺失 / 名称冲突等
 * 加载期不可恢复的错误。
 *
 * @author sureai
 * @since 2.9.0
 */
public class SkillLoadException extends RuntimeException {

	/** 序列化版本号。 */
	private static final long serialVersionUID = 1L;

	/**
	 * 构造异常。
	 *
	 * @param message 错误信息
	 */
	public SkillLoadException(String message) {
		super(message);
	}

	/**
	 * 构造异常（带cause）。
	 *
	 * @param message 错误信息
	 * @param cause   底层原因
	 */
	public SkillLoadException(String message, Throwable cause) {
		super(message, cause);
	}
}
