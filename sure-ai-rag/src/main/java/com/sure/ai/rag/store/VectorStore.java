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

package com.sure.ai.rag.store;

import java.util.List;

import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;

/**
 * 向量存储抽象：负责向量条目的写入、删除与相似度检索。
 *
 * <p>当前内置 {@link InMemoryVectorStore}（进程内、余弦相似度、暴力检索）。
 * 实现该接口可接入 Milvus、FAISS、pgvector、Elasticsearch 等外部向量库，
 * 检索语义由实现方保证（按相似度降序返回前 topK 条）。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface VectorStore {

	/**
	 * 写入单条向量（同 id 重复写入视为覆盖更新）。
	 *
	 * @param vector 向量条目
	 */
	void add(Vector vector);

	/**
	 * 批量写入向量。
	 *
	 * @param vectors 向量列表，可为空
	 */
	void addAll(List<Vector> vectors);

	/**
	 * 按标识删除向量。
	 *
	 * @param id 向量标识
	 * @return 是否删除成功（不存在返回 false）
	 */
	boolean delete(String id);

	/**
	 * 清空全部向量。
	 */
	void clear();

	/**
	 * 返回当前向量条数。
	 *
	 * @return 条数
	 */
	int size();

	/**
	 * 按查询向量做相似度检索，返回相似度降序的前 topK 条。
	 *
	 * @param queryEmbedding 查询向量
	 * @param topK 返回条数，必须大于 0
	 * @return 相似度降序结果，不会返回 null
	 */
	List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK);

	/**
	 * 按查询向量做相似度检索，并过滤低于最低相似度的结果。
	 *
	 * @param queryEmbedding 查询向量
	 * @param topK 返回条数，必须大于 0
	 * @param minScore 最低相似度阈值（含），低于该值的条目被过滤
	 * @return 相似度降序结果
	 */
	List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK, double minScore);
}
