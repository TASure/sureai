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
package com.sure.ai.rag.strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.rewriter.ModelQueryRewriter;
import com.sure.ai.rag.rewriter.QueryRewriter;
import com.sure.tool.lang.Assert;

/**
 * Multi-Query（多查询）检索器：把复杂问题改写/分解为多个子查询，分别检索后用
 * RRF（Reciprocal Rank Fusion，倒数排名融合）合并去重。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>用 {@link QueryRewriter} 把原始 query 改写为 {@code queryCount} 个子查询；</li>
 *   <li>对每个子查询分别调用底层 {@link Retriever} 召回 topK 个文档；</li>
 *   <li>对每路结果按排名做 RRF 融合：{@code score = Σ 1/(k + rank)}（rank 从 1 开始），
 *       同一文档在多路出现时其 RRF 得分累加；</li>
 *   <li>按融合得分降序取前 topK，同 id 去重（保留首次出现的文档正文与元数据）。</li>
 * </ol>
 *
 * <p>容错：改写器异常或返回空时退化为仅用原始 query 检索（{@link ModelQueryRewriter}
 * 已内置该回退；自定义实现也应保证返回非空列表）。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class MultiQueryRetriever implements Retriever {

	/** 默认子查询数量。 */
	public static final int DEFAULT_QUERY_COUNT = 3;
	/** 默认 RRF 常数 k。 */
	public static final double DEFAULT_RRF_K = 60.0;

	private final Retriever retriever;
	private final QueryRewriter rewriter;
	private final int queryCount;
	private final double rrfK;

	private MultiQueryRetriever(Builder builder) {
		this.retriever = builder.retriever;
		this.rewriter = builder.rewriter;
		this.queryCount = builder.queryCount;
		this.rrfK = builder.rrfK;
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public List<Document> retrieve(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);

		// 1. 改写/分解子查询（失败时 rewriter 应返回单元素原 query）
		List<String> subQueries = this.rewriter.rewrite(query, this.queryCount);
		if (subQueries == null || subQueries.isEmpty()) {
			subQueries = List.of(query);
		}

		// 2. 逐路召回并累计 RRF 得分
		Map<String, Double> rrfScores = new LinkedHashMap<>();
		Map<String, Document> merged = new LinkedHashMap<>();
		for (String subQuery : subQueries) {
			List<Document> hits = this.retriever.retrieve(subQuery, topK);
			if (hits == null) {
				continue;
			}
			for (int rank = 0; rank < hits.size(); rank++) {
				Document doc = hits.get(rank);
				if (doc == null) {
					continue;
				}
				// rank 从 1 开始：第一名贡献 1/(k+1)
				double contribution = 1.0 / (this.rrfK + rank + 1);
				rrfScores.merge(doc.id(), contribution, Double::sum);
				// 同 id 去重：保留首次出现的文档
				merged.putIfAbsent(doc.id(), doc);
			}
		}

		// 3. 按 RRF 得分降序，取前 topK
		List<Map.Entry<String, Double>> ranked = new ArrayList<>(rrfScores.entrySet());
		ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		int limit = Math.min(topK, ranked.size());
		List<Document> result = new ArrayList<>(limit);
		for (int i = 0; i < limit; i++) {
			result.add(merged.get(ranked.get(i).getKey()));
		}
		return result;
	}

	/**
	 * MultiQueryRetriever 构造器。
	 */
	public static final class Builder {

		private Retriever retriever;
		private QueryRewriter rewriter;
		private int queryCount = DEFAULT_QUERY_COUNT;
		private double rrfK = DEFAULT_RRF_K;

		private Builder() {
		}

		/**
		 * 设置底层检索器（必填）。
		 *
		 * @param retriever 底层检索器
		 * @return this
		 */
		public Builder retriever(Retriever retriever) {
			this.retriever = retriever;
			return this;
		}

		/**
		 * 设置查询改写器（必填）。
		 *
		 * @param rewriter 查询改写器
		 * @return this
		 */
		public Builder rewriter(QueryRewriter rewriter) {
			this.rewriter = rewriter;
			return this;
		}

		/**
		 * 设置子查询数量。
		 *
		 * @param queryCount 子查询数量，必须大于 0
		 * @return this
		 */
		public Builder queryCount(int queryCount) {
			this.queryCount = queryCount;
			return this;
		}

		/**
		 * 设置 RRF 常数 k。
		 *
		 * @param rrfK RRF 常数，必须大于 0
		 * @return this
		 */
		public Builder rrfK(double rrfK) {
			this.rrfK = rrfK;
			return this;
		}

		/**
		 * 构建多查询检索器。
		 *
		 * @return 检索器
		 */
		public MultiQueryRetriever build() {
			Assert.notNull(this.retriever, "retriever 不能为 null");
			Assert.notNull(this.rewriter, "rewriter 不能为 null");
			Assert.isTrue(this.queryCount > 0, "queryCount 必须大于 0，实际为 {}", this.queryCount);
			Assert.isTrue(this.rrfK > 0, "rrfK 必须大于 0，实际为 {}", this.rrfK);
			return new MultiQueryRetriever(this);
		}
	}
}
