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
 * 工具函数定义。
 *
 * @param name        函数名
 * @param description 函数描述
 * @param parameters  参数 JSON Schema 字符串
 * @author sureai
 * @since 0.1.0
 */
public record ToolFunction(String name, String description, String parameters) {

	/**
	 * 静态工厂。
	 *
	 * @param name        函数名
	 * @param description 函数描述
	 * @param parameters  参数 JSON Schema
	 * @return 工具函数
	 */
	public static ToolFunction of(String name, String description, String parameters) {
		return new ToolFunction(name, description, parameters);
	}
}
