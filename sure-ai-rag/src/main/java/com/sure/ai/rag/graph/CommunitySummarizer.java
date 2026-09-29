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

import java.util.List;

import com.sure.ai.rag.model.Document;

/**
 * 社区摘要器：为一个图社区生成一段主题摘要，作为后续检索的上下文。
 *
 * @author sureai
 * @since 1.8.0
 */
public interface CommunitySummarizer {

	/**
	 * 生成社区摘要。实现需保证失败时回退为机械拼接而非抛出异常。
	 *
	 * @param community  社区（不含摘要）
	 * @param graph      知识图谱
	 * @param sourceDocs 来源文档全集，可用于补充相关片段
	 * @return 社区主题摘要，不会返回 null
	 */
	String summarize(GraphCommunity community, KnowledgeGraph graph, List<Document> sourceDocs);
}
