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
package com.sure.ai.rag.strategy;

import java.util.List;

import com.sure.ai.rag.model.Document;

/**
 * 网络搜索兜底提供者：CRAG 在本地知识库全部不相关时，用于走外部搜索纠正。
 *
 * <p>本模块零网络依赖，不内置任何 HTTP 调用；由使用方注入具体实现
 * （如对接 Bing/Google/SerpAPI 或企业内部搜索网关）。实现应返回与查询相关的
 * 文档列表，且不得返回 null。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
@FunctionalInterface
public interface WebSearchProvider {

	/**
	 * 执行网络搜索。
	 *
	 * @param query 查询语句
	 * @param k 期望返回条数
	 * @return 搜索结果文档列表，不会返回 null
	 */
	List<Document> search(String query, int k);
}
