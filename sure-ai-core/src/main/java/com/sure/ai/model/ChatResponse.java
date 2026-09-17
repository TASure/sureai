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

import java.util.List;

/**
 * 对话响应。
 *
 * @param id     响应 ID
 * @param model  模型名
 * @param choices 候选列表
 * @param usage   用量
 * @param rawJson 原始响应 JSON
 * @author sureai
 * @since 0.1.0
 */
public record ChatResponse(String id, String model, List<Choice> choices,
		TokenUsage usage, String rawJson) {

	/**
	 * 全参构造器（防御性拷贝）。
	 */
	public ChatResponse {
		choices = choices == null ? List.of() : List.copyOf(choices);
	}

	/**
	 * 静态工厂。
	 *
	 * @param id     响应 ID
	 * @param model  模型名
	 * @param choices 候选列表
	 * @param usage   用量
	 * @param rawJson 原始 JSON
	 * @return 响应
	 */
	public static ChatResponse of(String id, String model, List<Choice> choices,
			TokenUsage usage, String rawJson) {
		return new ChatResponse(id, model, choices, usage, rawJson);
	}

	/**
	 * 取第一条候选的文本内容。
	 *
	 * @return 文本内容，无候选返回 null
	 */
	public String firstText() {
		if (this.choices.isEmpty()) {
			return null;
		}
		return this.choices.get(0).message().content();
	}
}
