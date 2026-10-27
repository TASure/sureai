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

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.client.RerankClient;
import com.sure.ai.model.RerankRequest;
import com.sure.ai.model.RerankResponse;
import com.sure.ai.model.RerankResult;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.tool.lang.Assert;

/**
 * 基于 sure-ai-core {@link RerankClient} 的重排器适配器。
 *
 * <p>将向量召回候选文档的文本作为 documents 提交给重排服务，按返回的
 * {@code relevanceScore} 重新排序，并以重排得分覆盖原相似度得分。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class ClientReranker implements Reranker {

	private final RerankClient client;
	private final String model;

	/**
	 * 构造适配器。
	 *
	 * @param client 重排客户端
	 * @param model  重排模型名
	 */
	public ClientReranker(RerankClient client, String model) {
		this.client = Assert.notNull(client, "client 不能为 null");
		this.model = Assert.notBlank(model, "model 不能为 blank");
	}

	@Override
	public List<SimilaritySearchResult> rerank(String query, List<SimilaritySearchResult> documents) {
		Assert.notNull(query, "query 不能为 null");
		if (documents == null || documents.isEmpty()) {
			return documents == null ? List.of() : documents;
		}
		List<String> texts = new ArrayList<>(documents.size());
		for (SimilaritySearchResult doc : documents) {
			texts.add(doc.text());
		}
		RerankRequest request = RerankRequest.builder()
			.model(this.model)
			.query(query)
			.documents(texts)
			.build();
		RerankResponse response = this.client.rerank(request);
		List<SimilaritySearchResult> out = new ArrayList<>(response.results().size());
		for (RerankResult result : response.results()) {
			int idx = result.index();
			if (idx < 0 || idx >= documents.size()) {
				continue;
			}
			SimilaritySearchResult orig = documents.get(idx);
			out.add(new SimilaritySearchResult(orig.id(), orig.embedding(), orig.text(),
				orig.metadata(), result.relevanceScore()));
		}
		return out;
	}

	/**
	 * 返回重排客户端（调试用）。
	 *
	 * @return 重排客户端
	 */
	public RerankClient client() {
		return this.client;
	}

	/**
	 * 返回重排模型名。
	 *
	 * @return 模型名
	 */
	public String model() {
		return this.model;
	}
}
