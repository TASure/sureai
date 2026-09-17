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
 * 向量响应。
 *
 * @param model      模型名
 * @param embeddings 每条输入对应的向量
 * @param usage      用量
 * @author sureai
 * @since 0.1.0
 */
public record EmbeddingResponse(String model, List<float[]> embeddings, TokenUsage usage) {

	/**
	 * 全参构造器（防御性拷贝）。
	 */
	public EmbeddingResponse {
		embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
	}

	/**
	 * 静态工厂。
	 *
	 * @param model      模型名
	 * @param embeddings 向量列表
	 * @param usage      用量
	 * @return 响应
	 */
	public static EmbeddingResponse of(String model, List<float[]> embeddings, TokenUsage usage) {
		return new EmbeddingResponse(model, embeddings, usage);
	}
}
