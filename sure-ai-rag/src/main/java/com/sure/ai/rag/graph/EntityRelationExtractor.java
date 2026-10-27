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

package com.sure.ai.rag.graph;

import com.sure.ai.rag.model.Document;

/**
 * 实体/关系抽取器：从单篇文档中抽取命名实体及其关系并写入知识图谱。
 *
 * <p>实现需保证：抽取失败（如 LLM 调用异常）时不向 {@code graph} 写入脏数据、
 * 也不向上抛出异常，以保证索引链路可继续处理后续文档。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public interface EntityRelationExtractor {

	/**
	 * 从文档中抽取实体与关系，并入图谱。
	 *
	 * @param doc   输入文档，不可为 null
	 * @param graph 目标知识图谱，不可为 null
	 */
	void extractInto(Document doc, KnowledgeGraph graph);
}
