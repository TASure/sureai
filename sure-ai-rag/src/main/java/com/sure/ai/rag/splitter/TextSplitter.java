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

package com.sure.ai.rag.splitter;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.rag.model.Document;

/**
 * 文本分块器：将长文本切分为适合向量化的分块。
 *
 * <p>分块策略直接影响检索质量：块过小丢失语义、块过大稀释相似度。
 * 建议块大小 500~1000 字符、重叠 10%~20%，并按语言选择合适的分隔符。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface TextSplitter {

	/**
	 * 将文本切分为分块列表。
	 *
	 * <p>实现须保证：返回列表不含 null 元素；空文本返回空列表；
	 * 每个分块非空（空白/空块应被剔除）。</p>
	 *
	 * @param text 原始文本，可为 null
	 * @return 分块列表，不会返回 null
	 */
	List<String> split(String text);

	/**
	 * 将文本切分为带标识的文档分块。
	 *
	 * @param sourceId 来源标识（作为文档 id 前缀，形如 {@code sourceId#0}）
	 * @param text 原始文本
	 * @return 文档分块列表
	 */
	default List<Document> splitToDocuments(String sourceId, String text) {
		List<String> chunks = split(text);
		List<Document> documents = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			documents.add(Document.of(sourceId + "#" + i, chunks.get(i)));
		}
		return documents;
	}
}
