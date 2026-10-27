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

import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * MCP 资源内容（resources/read 的一条）。
 *
 * @param uri      资源 URI
 * @param mimeType MIME 类型（可空）
 * @param text     文本内容（可空，二进制资源留空）
 * @author sureai
 * @since 1.2.0
 */
public record McpResourceContent(String uri, String mimeType, String text) {

	/**
	 * 从 resources/read 的 result 对象解析所有 content 项（取首条文本）。
	 *
	 * @param result result JSON 对象
	 * @return 资源内容列表
	 */
	public static java.util.List<McpResourceContent> fromResult(JsonObject result) {
		java.util.List<McpResourceContent> out = new java.util.ArrayList<>();
		if (result.has("contents")) {
			JsonArray arr = result.getJsonArray("contents");
			for (int i = 0; i < arr.size(); i++) {
				JsonObject o = arr.get(i).getAsJsonObject();
				out.add(new McpResourceContent(
					o.optString("uri", ""),
					o.optString("mimeType", ""),
					o.optString("text", "")));
			}
		}
		return out;
	}

	/**
	 * 从任意 result 元素安全解析。
	 *
	 * @param result result 元素
	 * @return 资源内容列表
	 */
	public static java.util.List<McpResourceContent> fromElement(JsonElement result) {
		if (result != null && result.isObject()) {
			return fromResult(result.getAsJsonObject());
		}
		return java.util.List.of();
	}
}
