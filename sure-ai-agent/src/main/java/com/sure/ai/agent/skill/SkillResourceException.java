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
 * 技能资源访问异常：资源不存在、路径穿越（{@code ..} / 绝对路径 / 反斜杠）等。
 *
 * @author sureai
 * @since 2.9.0
 */
public class SkillResourceException extends RuntimeException {

	/** 序列化版本号。 */
	private static final long serialVersionUID = 1L;

	/**
	 * 构造异常。
	 *
	 * @param message 错误信息
	 */
	public SkillResourceException(String message) {
		super(message);
	}

	/**
	 * 构造异常（带cause）。
	 *
	 * @param message 错误信息
	 * @param cause   底层原因
	 */
	public SkillResourceException(String message, Throwable cause) {
		super(message, cause);
	}
}
