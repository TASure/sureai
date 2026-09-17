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

package com.sure.ai.rag;

import java.util.List;

import com.sure.ai.rag.model.SimilaritySearchResult;

/**
 * 检索结果重排器（Reranker）抽象。
 *
 * <p>在向量召回之后，用交叉编码器或服务端重排模型对候选文档按与查询的真实相关性重新排序。
 * 输入为向量召回的候选列表，输出为重排后的列表（得分以重排模型为准）。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
@FunctionalInterface
public interface Reranker {

	/**
	 * 对候选文档重排。
	 *
	 * @param query     用户查询
	 * @param documents 向量召回的候选文档（带初始相似度得分）
	 * @return 重排后的文档列表（按重排得分降序）
	 */
	List<SimilaritySearchResult> rerank(String query, List<SimilaritySearchResult> documents);
}
