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

package com.sure.ai.rag.model;

import java.util.Collections;
import java.util.Map;

/**
 * 相似度检索结果：向量命中条目及其与查询向量的余弦相似度得分。
 *
 * @param id 命中条目标识
 * @param embedding 命中条目的嵌入向量
 * @param text 命中条目的原始文本
 * @param metadata 命中条目的元数据
 * @param score 相似度得分（余弦相似度，取值 [-1, 1]，越高越相似）
 * @author sureai
 * @since 0.2.0
 */
public record SimilaritySearchResult(String id, float[] embedding, String text,
		Map<String, String> metadata, double score) {

	/**
	 * 紧凑构造器：元数据为 null 时规范化为空 Map，并复制嵌入向量。
	 *
	 * @param id 命中条目标识
	 * @param embedding 命中条目的嵌入向量
	 * @param text 命中条目的原始文本
	 * @param metadata 命中条目的元数据
	 * @param score 相似度得分
	 */
	public SimilaritySearchResult {
		if (metadata == null) {
			metadata = Collections.emptyMap();
		}
		if (embedding != null) {
			embedding = embedding.clone();
		}
	}

	/**
	 * 返回嵌入向量的防御性副本。
	 *
	 * @return 嵌入向量副本
	 */
	@Override
	public float[] embedding() {
		return embedding == null ? null : embedding.clone();
	}

	/**
	 * 返回不可变的元数据视图。
	 *
	 * @return 元数据
	 */
	@Override
	public Map<String, String> metadata() {
		return Collections.unmodifiableMap(metadata);
	}
}
