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
 * MCP 提示模板渲染结果。
 *
 * @param textContents messages 中所有 text 片段拼接
 * @author sureai
 * @since 1.2.0
 */
public record McpPromptResult(List<String> textContents) {

	/** 紧凑构造。 */
	public McpPromptResult {
		textContents = List.copyOf(textContents);
	}

	/**
	 * 从 prompts/get 的 result 对象解析。
	 *
	 * @param result result JSON 对象
	 * @return 提示结果
	 */
	public static McpPromptResult fromResult(JsonObject result) {
		List<String> texts = new ArrayList<>();
		if (result.has("messages")) {
			JsonArray messages = result.getJsonArray("messages");
			for (int i = 0; i < messages.size(); i++) {
				JsonObject msg = messages.get(i).getAsJsonObject();
				if (msg.has("content")) {
					JsonObject content = msg.getJsonObject("content");
					if ("text".equals(content.optString("type", "text")) && content.has("text")) {
						texts.add(content.getString("text"));
					}
				}
			}
		}
		return new McpPromptResult(texts);
	}

	/**
	 * 从任意 result 元素安全解析。
	 *
	 * @param result result 元素
	 * @return 提示结果
	 */
	public static McpPromptResult fromElement(JsonElement result) {
		if (result != null && result.isObject()) {
			return fromResult(result.getAsJsonObject());
		}
		return new McpPromptResult(List.of());
	}

	/**
	 * 拼接全部文本。
	 *
	 * @return 文本
	 */
	public String asText() {
		return String.join("\n", this.textContents);
	}
}
