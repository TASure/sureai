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

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * MCP 工具调用结果。
 *
 * @param isError      是否为错误结果
 * @param textContents 所有 text 类型 content 片段（按顺序）
 * @author sureai
 * @since 1.2.0
 */
public record McpToolResult(boolean isError, List<String> textContents) {

	/**
	 * 紧凑构造：拷贝列表防御。
	 *
	 * @param isError      是否错误
	 * @param textContents 文本片段
	 */
	public McpToolResult {
		textContents = List.copyOf(textContents);
	}

	/**
	 * 从 tools/call 的 result 对象解析。
	 *
	 * @param result result JSON 对象
	 * @return 工具结果
	 */
	public static McpToolResult fromResult(JsonObject result) {
		boolean isError = result.has("isError") && result.getBoolean("isError");
		List<String> texts = new ArrayList<>();
		if (result.has("content")) {
			JsonArray content = result.getJsonArray("content");
			for (int i = 0; i < content.size(); i++) {
				JsonObject item = content.get(i).getAsJsonObject();
				if ("text".equals(item.optString("type", "text")) && item.has("text")) {
					texts.add(item.getString("text"));
				}
			}
		}
		return new McpToolResult(isError, texts);
	}

	/**
	 * 把全部文本片段拼成一段（换行连接）。
	 *
	 * @return 拼接文本
	 */
	public String asText() {
		return String.join("\n", this.textContents);
	}

	/**
	 * 是否含有文本内容。
	 *
	 * @return 有文本返回 true
	 */
	public boolean hasText() {
		return !this.textContents.isEmpty();
	}

	/**
	 * 从任意 result 元素安全构造（非对象时退化为空文本）。
	 *
	 * @param result result 元素
	 * @return 工具结果
	 */
	public static McpToolResult fromElement(JsonElement result) {
		if (result != null && result.isObject()) {
			return fromResult(result.getAsJsonObject());
		}
		return new McpToolResult(false, List.of());
	}
}
