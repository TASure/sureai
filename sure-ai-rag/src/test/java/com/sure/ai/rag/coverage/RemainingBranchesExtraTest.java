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

import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.rewriter.QueryRewriter;
import com.sure.ai.rag.strategy.MultiQueryRetriever;

/**
 * MultiQueryRetriever 边界分支补覆盖：rewriter 返回 null/空列表 → 回退单 query。
 *
 * @author sureai
 * @since 2.6.0
 */
public class RemainingBranchesExtraTest {

	/** MultiQueryRetriever：rewriter 返回 null → 回退 List.of(query)。 */
	@Test
	public void testMultiQueryRewriterReturnsNull() {
		QueryRewriter nullRewriter = new QueryRewriter() {
			@Override
			public List<String> rewrite(String query, int n) {
				return null;
			}
		};
		Retriever emptyRetriever = (q, k) -> List.of();
		MultiQueryRetriever mqr = MultiQueryRetriever.builder()
				.retriever(emptyRetriever)
				.rewriter(nullRewriter)
				.queryCount(3)
				.rrfK(60)
				.build();
		List<com.sure.ai.rag.model.Document> result = mqr.retrieve("test query", 5);
		assertNotNull(result);
	}

	/** MultiQueryRetriever：rewriter 返回空列表 → 回退单 query。 */
	@Test
	public void testMultiQueryRewriterReturnsEmpty() {
		QueryRewriter emptyRewriter = new QueryRewriter() {
			@Override
			public List<String> rewrite(String query, int n) {
				return List.of();
			}
		};
		Retriever emptyRetriever = (q, k) -> List.of();
		MultiQueryRetriever mqr = MultiQueryRetriever.builder()
				.retriever(emptyRetriever)
				.rewriter(emptyRewriter)
				.queryCount(3)
				.rrfK(60)
				.build();
		List<com.sure.ai.rag.model.Document> result = mqr.retrieve("test query", 5);
		assertNotNull(result);
	}
}
