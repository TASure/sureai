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
 * MCP 资源描述。
 *
 * @param uri         资源 URI
 * @param name        资源名
 * @param description 资源描述（可空）
 * @author sureai
 * @since 1.2.0
 */
public record McpResource(String uri, String name, String description) {

	/**
	 * 从 resources/list 数组元素解析。
	 *
	 * @param obj JSON 对象
	 * @return 资源描述
	 */
	public static McpResource fromJson(JsonObject obj) {
		String uri = obj.getString("uri");
		String name = obj.optString("name", uri);
		String desc = obj.optString("description", "");
		return new McpResource(uri, name, desc);
	}
}
