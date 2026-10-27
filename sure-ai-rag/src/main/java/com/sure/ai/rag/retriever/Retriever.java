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

package com.sure.ai.rag.retriever;

import java.util.List;

import com.sure.ai.rag.model.Document;

/**
 * 检索器抽象：将用户查询转换为相关文档列表。
 *
 * <p>当前内置基于向量相似度的 {@link VectorRetriever}；实现该接口可接入
 * 关键词检索（BM25）、混合检索、重排序（Rerank）等策略。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface Retriever {

	/**
	 * 检索与查询最相关的前 topK 个文档。
	 *
	 * @param query 用户查询
	 * @param topK 返回条数，必须大于 0
	 * @return 相关文档列表（按相关度降序），不会返回 null
	 */
	List<Document> retrieve(String query, int topK);
}
