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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.rag.embedding.ClientEmbeddingProvider;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.rewriter.QueryRewriter;
import com.sure.ai.rag.strategy.MultiQueryRetriever;

/**
 * ClientEmbeddingProvider getter + MultiQueryRetriever null-hit 分支补覆盖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class ClientEmbeddingProviderExtraTest {

	/** 空实现 EmbeddingClient。 */
	private static final EmbeddingClient NOOP_EMBED_CLIENT = new EmbeddingClient() {
		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			return null;
		}
	};

	/** ClientEmbeddingProvider getter。 */
	@Test
	public void testClientEmbeddingProviderGetters() {
		ClientEmbeddingProvider p = new ClientEmbeddingProvider(NOOP_EMBED_CLIENT, "test-model");
		assertEquals(NOOP_EMBED_CLIENT, p.client());
		assertEquals("test-model", p.model());
	}

	/** ClientEmbeddingProvider.embedAll(空列表) → emptyList。 */
	@Test
	public void testEmbedAllEmpty() {
		ClientEmbeddingProvider p = new ClientEmbeddingProvider(NOOP_EMBED_CLIENT, "test-model");
		assertTrue(p.embedAll(List.of()).isEmpty());
	}

	/** MultiQueryRetriever：子查询命中 null → continue 分支。 */
	@Test
	public void testMultiQueryNullHits() {
		QueryRewriter identityRewriter = new QueryRewriter() {
			@Override
			public List<String> rewrite(String query, int n) {
				return List.of(query);
			}
		};
		Retriever nullRetriever = (q, k) -> null;
		MultiQueryRetriever mqr = MultiQueryRetriever.builder()
				.retriever(nullRetriever)
				.rewriter(identityRewriter)
				.queryCount(3)
				.rrfK(60)
				.build();
		List<com.sure.ai.rag.model.Document> result = mqr.retrieve("test", 5);
		assertNotNull(result);
	}
}
