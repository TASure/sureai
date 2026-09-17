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

package com.sure.ai.model;

import java.util.Arrays;

import com.sure.ai.exception.AiException;

/**
 * 对话角色。
 *
 * @author sureai
 * @since 0.1.0
 */
public enum Role {

	/** 系统指令。 */
	SYSTEM("system"),

	/** 用户消息。 */
	USER("user"),

	/** 助手消息。 */
	ASSISTANT("assistant"),

	/** 工具返回。 */
	TOOL("tool");

	/** 线控字符串值 */
	private final String value;

	Role(String value) {
		this.value = value;
	}

	/**
	 * 返回线控字符串值。
	 *
	 * @return 字符串值
	 */
	public String value() {
		return this.value;
	}

	/**
	 * 按线控字符串解析角色。
	 *
	 * @param value 字符串值
	 * @return 对应角色
	 * @throws AiException 未知值时抛出
	 */
	public static Role fromValue(String value) {
		return Arrays.stream(values())
			.filter(r -> r.value.equals(value))
			.findFirst()
			.orElseThrow(() -> new AiException("Unknown role: " + value));
	}
}
