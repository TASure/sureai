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
 * Token 用量。
 *
 * @param promptTokens     提示 token 数
 * @param completionTokens 补全 token 数
 * @param totalTokens      总 token 数
 * @author sureai
 * @since 0.1.0
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

	/**
	 * 静态工厂。
	 *
	 * @param promptTokens     提示 token 数
	 * @param completionTokens 补全 token 数
	 * @param totalTokens      总 token 数
	 * @return 用量
	 */
	public static TokenUsage of(int promptTokens, int completionTokens, int totalTokens) {
		return new TokenUsage(promptTokens, completionTokens, totalTokens);
	}
}
