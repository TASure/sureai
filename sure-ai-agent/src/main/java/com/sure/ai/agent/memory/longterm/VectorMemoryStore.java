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

package com.sure.ai.agent.memory.longterm;

import java.util.List;

/**
 * 支持向量检索的长期记忆存储。
 *
 * <p>在 {@link MemoryStore} 的键值能力之上，增加基于向量相似度与文本包含的检索。
 * 无向量的条目参与 {@link #searchByText(String, int)} 子串匹配。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public interface VectorMemoryStore extends MemoryStore {

	/**
	 * 按查询向量做余弦相似度 Top-K 检索。
	 *
	 * @param queryVector 查询向量（非 null）
	 * @param k           返回条数（&ge;1）
	 * @return 相似度从高到低的条目列表
	 */
	List<MemoryEntry> search(float[] queryVector, int k);

	/**
	 * 按查询文本做子串/包含匹配（无向量化时的降级路径）。
	 *
	 * @param query 查询文本（非空白）
	 * @param k     返回条数（&ge;1）
	 * @return 命中条目列表（按匹配度/插入时间排序）
	 */
	List<MemoryEntry> searchByText(String query, int k);
}
