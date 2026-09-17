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

/**
 * 工具调用记录。
 *
 * @param id             调用 ID
 * @param name           函数名
 * @param argumentsJson  参数字符串（JSON）
 * @author sureai
 * @since 0.1.0
 */
public record ToolCall(String id, String name, String argumentsJson) {

	/**
	 * 静态工厂。
	 *
	 * @param id            调用 ID
	 * @param name          函数名
	 * @param argumentsJson 参数 JSON
	 * @return 工具调用
	 */
	public static ToolCall of(String id, String name, String argumentsJson) {
		return new ToolCall(id, name, argumentsJson);
	}
}
