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

package com.sure.ai.rag.retriever;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.tool.lang.Assert;

/**
 * 混合检索器：融合向量语义检索与 BM25 关键词检索的加权结果。
 *
 * <p>融合流程：</p>
 * <ol>
 *   <li>两路各自召回 {@code topK*2} 个候选（扩大召回池）；</li>
 *   <li>对每路得分做 min-max 归一化映射到 {@code [0,1]}（单路仅一条正值得 1.0、全 0 得 0）；</li>
 *   <li>按文档 id 合并：{@code finalScore = wv * normVec + wk * normKw}，
 *       仅在一路出现的文档另一路得分为 0；</li>
 *   <li>按融合得分降序取前 topK。</li>
 * </ol>
 *
 * <p>权重默认 0.5/0.5，不要求权重和为 1（内部自动归一化）。接受
 * {@link VectorRetriever}（具体类，可获取得分；若配置了 reranker，
 * {@code retrieveWithScores} 返回的即为重排后得分）与 {@link KeywordRetriever}。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public class HybridRetriever implements Retriever {

	/** 默认向量权重 */
	public static final double DEFAULT_VECTOR_WEIGHT = 0.5;
	/** 默认关键词权重 */
	public static final double DEFAULT_KEYWORD_WEIGHT = 0.5;

	private final VectorRetriever vectorRetriever;
	private final KeywordRetriever keywordRetriever;
	private final double vectorWeight;
	private final double keywordWeight;

	/**
	 * 以默认权重 0.5/0.5 构造。
	 *
	 * @param vectorRetriever 向量检索器，不允许为 null
	 * @param keywordRetriever 关键词检索器，不允许为 null
	 */
	public HybridRetriever(VectorRetriever vectorRetriever, KeywordRetriever keywordRetriever) {
		this(vectorRetriever, keywordRetriever,
				DEFAULT_VECTOR_WEIGHT, DEFAULT_KEYWORD_WEIGHT);
	}

	/**
	 * 全参构造器。
	 *
	 * @param vectorRetriever 向量检索器，不允许为 null
	 * @param keywordRetriever 关键词检索器，不允许为 null
	 * @param vectorWeight 向量路权重，必须大于等于 0
	 * @param keywordWeight 关键词路权重，必须大于等于 0
	 */
	public HybridRetriever(VectorRetriever vectorRetriever, KeywordRetriever keywordRetriever,
			double vectorWeight, double keywordWeight) {
		this.vectorRetriever = Assert.notNull(vectorRetriever, "vectorRetriever 不能为 null");
		this.keywordRetriever = Assert.notNull(keywordRetriever, "keywordRetriever 不能为 null");
		Assert.isTrue(vectorWeight >= 0, "vectorWeight 必须大于等于 0，实际为 {}", vectorWeight);
		Assert.isTrue(keywordWeight >= 0, "keywordWeight 必须大于等于 0，实际为 {}", keywordWeight);
		Assert.isTrue(vectorWeight + keywordWeight > 0,
				"vectorWeight 与 keywordWeight 不能同时为 0");
		this.vectorWeight = vectorWeight;
		this.keywordWeight = keywordWeight;
	}

	@Override
	public List<Document> retrieve(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		int candidateK = topK * 2;

		// 1. 两路召回候选
		List<SimilaritySearchResult> vectorRaw =
				vectorRetriever.retrieveWithScores(query, candidateK);
		List<KeywordRetriever.Scored> keywordRaw =
				keywordRetriever.retrieveWithScores(query, candidateK);

		// 2. 各自 min-max 归一化
		Map<String, Double> normVector = normalizeVector(vectorRaw);
		Map<String, Double> normKeyword = normalizeKeyword(keywordRaw);

		// 3. 按 id 合并；保留文档正文与元数据
		double weightSum = vectorWeight + keywordWeight;
		double wv = vectorWeight / weightSum;
		double wk = keywordWeight / weightSum;

		Map<String, double[]> finalScores = new LinkedHashMap<>();
		Map<String, Document> mergedDocs = new HashMap<>();
		for (SimilaritySearchResult result : vectorRaw) {
			double score = wv * normVector.getOrDefault(result.id(), 0.0);
			finalScores.computeIfAbsent(result.id(), k -> new double[1])[0] += score;
			mergedDocs.putIfAbsent(result.id(),
					Document.of(result.id(), result.text(), result.metadata()));
		}
		for (KeywordRetriever.Scored scored : keywordRaw) {
			Document doc = scored.document();
			double score = wk * normKeyword.getOrDefault(doc.id(), 0.0);
			finalScores.computeIfAbsent(doc.id(), k -> new double[1])[0] += score;
			mergedDocs.putIfAbsent(doc.id(), doc);
		}

		// 4. 按融合得分降序，取前 topK
		List<Map.Entry<String, double[]>> ranked = new ArrayList<>(finalScores.entrySet());
		ranked.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
		int limit = Math.min(topK, ranked.size());
		List<Document> result = new ArrayList<>(limit);
		for (int i = 0; i < limit; i++) {
			String id = ranked.get(i).getKey();
			result.add(mergedDocs.get(id));
		}
		return result;
	}

	/**
	 * 对向量检索结果做 min-max 归一化。
	 *
	 * @param results 向量检索结果
	 * @return id → 归一化得分
	 */
	private static Map<String, Double> normalizeVector(List<SimilaritySearchResult> results) {
		Map<String, Double> map = new HashMap<>();
		if (results.isEmpty()) {
			return map;
		}
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		for (SimilaritySearchResult r : results) {
			min = Math.min(min, r.score());
			max = Math.max(max, r.score());
		}
		for (SimilaritySearchResult r : results) {
			map.put(r.id(), normalized(min, max, r.score()));
		}
		return map;
	}

	/**
	 * 对关键词检索结果做 min-max 归一化。
	 *
	 * @param results 关键词检索结果
	 * @return id → 归一化得分
	 */
	private static Map<String, Double> normalizeKeyword(List<KeywordRetriever.Scored> results) {
		Map<String, Double> map = new HashMap<>();
		if (results.isEmpty()) {
			return map;
		}
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		for (KeywordRetriever.Scored s : results) {
			min = Math.min(min, s.score());
			max = Math.max(max, s.score());
		}
		for (KeywordRetriever.Scored s : results) {
			map.put(s.document().id(), normalized(min, max, s.score()));
		}
		return map;
	}

	/**
	 * 单值归一化：全 0 → 0，单条/全相等正值得 1.0。
	 *
	 * @param min 最小得分
	 * @param max 最大得分
	 * @param score 待归一化得分
	 * @return [0,1] 归一化得分
	 */
	private static double normalized(double min, double max, double score) {
		if (max == min) {
			return max > 0 ? 1.0 : 0.0;
		}
		return (score - min) / (max - min);
	}

	/**
	 * 返回向量权重。
	 *
	 * @return 向量权重
	 */
	public double vectorWeight() {
		return vectorWeight;
	}

	/**
	 * 返回关键词权重。
	 *
	 * @return 关键词权重
	 */
	public double keywordWeight() {
		return keywordWeight;
	}
}
