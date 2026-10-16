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
package com.sure.ai.rag.coverage;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.graph.CommunityDetector;
import com.sure.ai.rag.graph.CommunitySummarizer;
import com.sure.ai.rag.graph.EntityRelationExtractor;
import com.sure.ai.rag.graph.GraphCommunity;
import com.sure.ai.rag.graph.GraphRagIndexer;
import com.sure.ai.rag.graph.GraphRagRetriever;
import com.sure.ai.rag.graph.KnowledgeGraph;
import com.sure.ai.rag.model.Document;

/**
 * GraphRagRetriever：空/空白查询返回空列表补覆盖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class GraphRagEmptyQueryExtraTest {

	/** GraphRagRetriever：空白查询 → 返回空列表。 */
	@Test
	public void testGraphRagEmptyQuery() {
		KnowledgeGraph graph = new KnowledgeGraph();
		EntityRelationExtractor extractor = (doc, g) -> { };
		CommunityDetector detector = (g) -> List.of();
		CommunitySummarizer summarizer = new CommunitySummarizer() {
			@Override
			public String summarize(GraphCommunity community, KnowledgeGraph g, List<Document> sourceDocs) {
				return "";
			}
		};
		GraphRagIndexer indexer = GraphRagIndexer.builder()
				.graph(graph)
				.extractor(extractor)
				.detector(detector)
				.summarizer(summarizer)
				.build();
		GraphRagRetriever retriever = GraphRagRetriever.builder().indexer(indexer).build();
		List<Document> result = retriever.retrieve("   ", 5);
		assertTrue(result.isEmpty());
	}
}
