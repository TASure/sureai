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
 * 向量请求。
 *
 * @param model 模型名
 * @param input 输入文本列表
 * @author sureai
 * @since 0.1.0
 */
public record EmbeddingRequest(String model, List<String> input) {

	/**
	 * 全参构造器（防御性拷贝）。
	 */
	public EmbeddingRequest {
		input = input == null ? List.of() : List.copyOf(input);
	}

	/**
	 * 静态工厂。
	 *
	 * @param model 模型名
	 * @param input 输入文本
	 * @return 请求
	 */
	public static EmbeddingRequest of(String model, List<String> input) {
		return new EmbeddingRequest(model, input);
	}
}
