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

package com.sure.ai.mcp.model;

import com.sure.ai.internal.json.JsonObject;

/**
 * MCP 提示模板描述。
 *
 * @param name        提示名
 * @param description 描述（可空）
 * @author sureai
 * @since 1.2.0
 */
public record McpPrompt(String name, String description) {

	/**
	 * 从 prompts/list 数组元素解析。
	 *
	 * @param obj JSON 对象
	 * @return 提示描述
	 */
	public static McpPrompt fromJson(JsonObject obj) {
		return new McpPrompt(obj.getString("name"), obj.optString("description", ""));
	}
}
