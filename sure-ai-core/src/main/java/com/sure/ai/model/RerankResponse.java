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
 * 重排响应。
 *
 * @param model   实际使用的重排模型
 * @param results 重排结果（按相关性降序）
 * @param rawJson 原始响应 JSON
 * @author sureai
 * @since 0.2.0
 */
public record RerankResponse(String model, List<RerankResult> results, String rawJson) {

	/**
	 * 紧凑构造器（防御性拷贝）。
	 *
	 * @param model   模型
	 * @param results 结果列表
	 * @param rawJson 原始 JSON
	 */
	public RerankResponse {
		results = results == null ? List.of() : List.copyOf(results);
	}

	/**
	 * 静态工厂。
	 *
	 * @param model   模型
	 * @param results 结果列表
	 * @param rawJson 原始 JSON
	 * @return 重排响应
	 */
	public static RerankResponse of(String model, List<RerankResult> results, String rawJson) {
		return new RerankResponse(model, results, rawJson);
	}

	/**
	 * 重排结果列表（便捷访问器）。
	 *
	 * @return 结果列表
	 */
	public List<RerankResult> results() {
		return this.results;
	}
}
