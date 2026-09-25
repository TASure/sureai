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

import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * MCP 工具描述。
 *
 * @param name        工具名
 * @param description 工具描述（可空）
 * @param inputSchema 入参 JSON Schema（原始 JSON 对象）
 * @author sureai
 * @since 1.2.0
 */
public record McpTool(String name, String description, JsonElement inputSchema) {

	/**
	 * 从 tools/list 数组元素解析。
	 *
	 * @param obj JSON 对象
	 * @return 工具描述
	 */
	public static McpTool fromJson(JsonObject obj) {
		String name = obj.getString("name");
		String desc = obj.optString("description", "");
		JsonElement schema = obj.has("inputSchema") ? obj.get("inputSchema") : null;
		return new McpTool(name, desc, schema);
	}
}
