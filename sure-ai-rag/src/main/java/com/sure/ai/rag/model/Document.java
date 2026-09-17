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
 * RAG 文档：一段可检索的文本及其元数据。
 *
 * <p>文档由 {@code TextSplitter} 从原始文本切分而来，经向量化后写入
 * {@code VectorStore}，检索命中后作为上下文拼入提示词。</p>
 *
 * @param id 文档唯一标识（如来源 ID + 分块序号）
 * @param text 文档正文
 * @param metadata 元数据（来源、标题、页码等，可为空）
 * @author sureai
 * @since 0.2.0
 */
public record Document(String id, String text, Map<String, String> metadata) {

	/**
	 * 创建无元数据文档。
	 *
	 * @param id 文档唯一标识
	 * @param text 文档正文
	 * @return 文档
	 */
	public static Document of(String id, String text) {
		return new Document(id, text, Collections.emptyMap());
	}

	/**
	 * 创建带元数据文档。
	 *
	 * @param id 文档唯一标识
	 * @param text 文档正文
	 * @param metadata 元数据，不允许为 null
	 * @return 文档
	 */
	public static Document of(String id, String text, Map<String, String> metadata) {
		return new Document(id, text, metadata);
	}

	/**
	 * 紧凑构造器：将空元数据规范化为不可变空 Map。
	 *
	 * @param id 文档唯一标识
	 * @param text 文档正文
	 * @param metadata 元数据，可为 null（规范化为空 Map）
	 */
	public Document {
		if (metadata == null) {
			metadata = Collections.emptyMap();
		}
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
