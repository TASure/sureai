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
 * 单条重排结果。
 *
 * @param index           命中文档在请求 documents 列表中的下标（0 起）
 * @param relevanceScore  相关性得分（越高越相关）
 * @param document        命中文档原文
 * @param rawJson         该条结果原始 JSON（便于调试与平台扩展字段提取）
 * @author sureai
 * @since 0.2.0
 */
public record RerankResult(int index, double relevanceScore, String document, String rawJson) {

	/**
	 * 静态工厂。
	 *
	 * @param index          文档下标
	 * @param relevanceScore 相关性得分
	 * @param document       文档原文
	 * @param rawJson        原始 JSON
	 * @return 重排结果
	 */
	public static RerankResult of(int index, double relevanceScore, String document, String rawJson) {
		return new RerankResult(index, relevanceScore, document, rawJson);
	}
}
