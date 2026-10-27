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

package com.sure.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.client.RerankClient;
import com.sure.ai.model.RerankRequest;
import com.sure.ai.model.RerankResponse;
import com.sure.ai.model.RerankResult;
import com.sure.ai.rag.embedding.ClientEmbeddingProvider;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.retriever.VectorRetriever;
import com.sure.ai.rag.store.InMemoryVectorStore;
import com.sure.ai.rag.store.VectorStore;

/**
 * RAG 重排链路测试：fake Reranker / fake RerankClient，零真实网络。
 *
 * @author sureai
 * @since 0.2.0
 */
public class RerankerTest {

	private VectorStore buildStore(String... texts) {
		InMemoryVectorStore store = new InMemoryVectorStore();
		int i = 0;
		for (String text : texts) {
			store.add(Vector.of("id-" + i, TestEmbeddingClient.hashEmbedding(text), text));
			i++;
		}
		return store;
	}

	/** 把向量检索结果反转顺序的 fake 重排器。 */
	private static final class ReverseReranker implements Reranker {
		private int callCount;
		private String lastQuery;

		@Override
		public List<SimilaritySearchResult> rerank(String query,
				List<SimilaritySearchResult> documents) {
			this.callCount++;
			this.lastQuery = query;
			List<SimilaritySearchResult> out = new ArrayList<>(documents.size());
			for (int i = documents.size() - 1; i >= 0; i--) {
				SimilaritySearchResult orig = documents.get(i);
				out.add(new SimilaritySearchResult(orig.id(), orig.embedding(), orig.text(),
						orig.metadata(), 1.0d / (i + 1)));
			}
			return out;
		}
	}

	/** fake 重排客户端：固定按倒序返回结果。 */
	private static final class FakeRerankClient implements RerankClient {
		private RerankRequest lastRequest;

		@Override
		public RerankResponse rerank(RerankRequest request) {
			this.lastRequest = request;
			List<RerankResult> results = new ArrayList<>(request.documents().size());
			for (int i = request.documents().size() - 1; i >= 0; i--) {
				results.add(RerankResult.of(i, 0.9d - i * 0.1d, request.documents().get(i), "{}"));
			}
			return RerankResponse.of("fake-reranker", results, "{}");
		}
	}

	/** VectorRetriever 配置 reranker 后，检索结果经重排。 */
	@Test
	public void testVectorRetrieverAppliesReranker() {
		VectorStore store = buildStore("苹果", "香蕉", "橙子");
		EmbeddingProvider provider = new ClientEmbeddingProvider(new TestEmbeddingClient(), "emb");
		// 基线：不重排的向量召回顺序
		VectorRetriever base = new VectorRetriever(store, provider);
		List<SimilaritySearchResult> baseline = base.retrieveWithScores("苹果", 3);

		ReverseReranker reranker = new ReverseReranker();
		VectorRetriever retriever = VectorRetriever.builder()
			.store(store).embeddingProvider(provider).reranker(reranker).build();

		List<SimilaritySearchResult> results = retriever.retrieveWithScores("苹果", 3);
		assertEquals(3, results.size());
		assertEquals(1, reranker.callCount);
		assertEquals("苹果", reranker.lastQuery);
		// 重排器反转基线顺序，首条应为基线末条
		assertEquals(baseline.get(2).id(), results.get(0).id());
		assertEquals(baseline.get(1).id(), results.get(1).id());
		assertEquals(baseline.get(0).id(), results.get(2).id());
		// 得分被重排器覆盖
		assertEquals(1.0d / 3.0d, results.get(0).score(), 1e-9);
	}

	/** VectorRetriever 未配置 reranker 时行为不变（向后兼容）。 */
	@Test
	public void testNoRerankerIsNoOp() {
		VectorStore store = buildStore("苹果", "香蕉");
		EmbeddingProvider provider = new ClientEmbeddingProvider(new TestEmbeddingClient(), "emb");
		VectorRetriever retriever = new VectorRetriever(store, provider);
		List<SimilaritySearchResult> results = retriever.retrieveWithScores("苹果", 2);
		assertEquals(2, results.size());
		assertEquals("id-0", results.get(0).id());
		assertTrue(retriever.reranker() == null);
	}

	/** ClientReranker：提交文本、按返回 index 重排并覆盖得分。 */
	@Test
	public void testClientRerankerReorder() {
		FakeRerankClient client = new FakeRerankClient();
		ClientReranker reranker = new ClientReranker(client, "bge-reranker");
		List<SimilaritySearchResult> docs = new ArrayList<>();
		docs.add(new SimilaritySearchResult("a", new float[]{1.0f}, "文本A", null, 0.5));
		docs.add(new SimilaritySearchResult("b", new float[]{2.0f}, "文本B", null, 0.4));
		docs.add(new SimilaritySearchResult("c", new float[]{3.0f}, "文本C", null, 0.3));

		List<SimilaritySearchResult> out = reranker.rerank("查询", docs);
		assertEquals(3, out.size());
		// fake 客户端按倒序返回：首条为原 index 2（id=c），得分被覆盖为 0.7
		assertEquals("c", out.get(0).id());
		assertEquals("b", out.get(1).id());
		assertEquals("a", out.get(2).id());
		assertEquals(0.7d, out.get(0).score(), 1e-9);
		assertEquals(0.9d, out.get(2).score(), 1e-9);
		// 透传模型与查询
		assertEquals("bge-reranker", client.lastRequest.model());
		assertEquals("查询", client.lastRequest.query());
		assertEquals(3, client.lastRequest.documents().size());
		assertSame(reranker.model(), "bge-reranker");
	}

	/** ClientReranker：空输入直接返回，不调用客户端。 */
	@Test
	public void testClientRerankerEmpty() {
		FakeRerankClient client = new FakeRerankClient();
		ClientReranker reranker = new ClientReranker(client, "m");
		assertTrue(reranker.rerank("q", List.of()).isEmpty());
		assertTrue(reranker.rerank("q", null).isEmpty());
	}
}
