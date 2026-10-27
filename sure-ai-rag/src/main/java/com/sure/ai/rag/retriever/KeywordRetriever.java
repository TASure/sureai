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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sure.ai.rag.model.Document;
import com.sure.tool.lang.Assert;

/**
 * 关键词检索器：基于 BM25 算法的纯 JDK 关键词相关性检索。
 *
 * <p>构造时对给定文档集合做词频/文档频率统计；查询时按经典 BM25 公式打分：</p>
 *
 * <pre>
 * score(D,Q) = Σ IDF(qi) * (f(qi,D) * (k1+1)) / (f(qi,D) + k1 * (1 - b + b * |D|/avgdl))
 * IDF(qi)    = ln(1 + (N - n(qi) + 0.5) / (n(qi) + 0.5))
 * </pre>
 *
 * <p>默认参数 k1=1.2、b=0.75（经典 BM25 取值）。分词策略：小写化后按
 * 非字母数字字符切分（{@code [^a-zA-Z0-9]+}），过滤空 token 与长度小于
 * {@code minTokenLength}（默认 2）的 token。纯 JDK 实现，零第三方依赖。</p>
 *
 * <p>注意：分词基于英文/数字边界，对中文等连续无空格语言支持有限——
 * 中文需先做分词预处理后再喂入；本类面向英文/数字关键词召回场景。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public class KeywordRetriever implements Retriever {

	/** 默认 BM25 k1 参数 */
	public static final double DEFAULT_K1 = 1.2;
	/** 默认 BM25 b 参数 */
	public static final double DEFAULT_B = 0.75;
	/** 默认最小 token 长度 */
	public static final int DEFAULT_MIN_TOKEN_LENGTH = 2;

	private final List<Document> documents;
	private final List<Map<String, Integer>> termFrequencies;
	private final Map<String, Integer> documentFrequency;
	private final double avgdl;
	private final int[] docLengths;
	private final double k1;
	private final double b;
	private final int minTokenLength;

	/**
	 * 以默认 BM25 参数构造。
	 *
	 * @param documents 文档集合，不允许为 null（内部保留不可变副本）
	 */
	public KeywordRetriever(List<Document> documents) {
		this(documents, DEFAULT_K1, DEFAULT_B, DEFAULT_MIN_TOKEN_LENGTH);
	}

	/**
	 * 全参构造器。
	 *
	 * @param documents 文档集合，不允许为 null
	 * @param k1 词频饱和参数，必须大于 0
	 * @param b 长度归一化参数，取值 [0, 1]
	 * @param minTokenLength 最小 token 长度，必须大于 0
	 */
	public KeywordRetriever(List<Document> documents, double k1, double b, int minTokenLength) {
		Assert.notNull(documents, "documents 不能为 null");
		Assert.isTrue(k1 > 0, "k1 必须大于 0，实际为 {}", k1);
		Assert.isTrue(b >= 0 && b <= 1, "b 必须位于 [0,1]，实际为 {}", b);
		Assert.isTrue(minTokenLength > 0, "minTokenLength 必须大于 0，实际为 {}", minTokenLength);
		this.documents = Collections.unmodifiableList(new ArrayList<>(documents));
		this.k1 = k1;
		this.b = b;
		this.minTokenLength = minTokenLength;
		this.documentFrequency = new HashMap<>();
		int n = this.documents.size();
		this.termFrequencies = new ArrayList<>(n);
		this.docLengths = new int[n];
		long totalLength = 0;
		for (int i = 0; i < n; i++) {
			List<String> tokens = tokenize(this.documents.get(i).text());
			Map<String, Integer> tf = new HashMap<>();
			Set<String> seen = new LinkedHashSet<>();
			for (String token : tokens) {
				tf.merge(token, 1, Integer::sum);
				seen.add(token);
			}
			this.termFrequencies.add(tf);
			this.docLengths[i] = tokens.size();
			totalLength += tokens.size();
			for (String token : seen) {
				this.documentFrequency.merge(token, 1, Integer::sum);
			}
		}
		this.avgdl = n > 0 ? (double) totalLength / n : 0.0;
	}

	@Override
	public List<Document> retrieve(String query, int topK) {
		List<Scored> scored = retrieveWithScores(query, topK);
		List<Document> result = new ArrayList<>(scored.size());
		for (Scored entry : scored) {
			result.add(entry.document());
		}
		return result;
	}

	/**
	 * 检索并保留 BM25 得分，供混合检索器做加权融合。
	 *
	 * @param query 用户查询
	 * @param topK 返回条数，必须大于 0
	 * @return 带得分的检索结果（按 BM25 得分降序）
	 */
	public List<Scored> retrieveWithScores(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		if (documents.isEmpty()) {
			return new ArrayList<>(0);
		}
		List<String> queryTokens = tokenize(query);
		int n = documents.size();
		double[] scores = new double[n];
		for (int i = 0; i < n; i++) {
			scores[i] = score(queryTokens, i);
		}
		// 稳定排序：得分相同保持原始文档顺序
		List<Integer> order = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			order.add(i);
		}
		order.sort((x, y) -> Double.compare(scores[y], scores[x]));
		int limit = Math.min(topK, n);
		List<Scored> result = new ArrayList<>(limit);
		for (int i = 0; i < limit; i++) {
			int idx = order.get(i);
			result.add(new Scored(documents.get(idx), scores[idx]));
		}
		return result;
	}

	/**
	 * 计算单个文档对查询的 BM25 得分。
	 *
	 * @param queryTokens 查询分词
	 * @param docIndex 文档下标
	 * @return BM25 得分
	 */
	private double score(List<String> queryTokens, int docIndex) {
		if (queryTokens.isEmpty()) {
			return 0.0;
		}
		int n = documents.size();
		Map<String, Integer> tf = termFrequencies.get(docIndex);
		int dl = docLengths[docIndex];
		double lengthNorm = avgdl > 0 ? 1 - b + b * ((double) dl / avgdl) : 1.0;
		double sum = 0.0;
		Set<String> counted = new LinkedHashSet<>();
		for (String token : queryTokens) {
			if (!counted.add(token)) {
				continue;
			}
			int df = documentFrequency.getOrDefault(token, 0);
			if (df == 0) {
				continue;
			}
			double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
			int f = tf.getOrDefault(token, 0);
			sum += idf * (f * (k1 + 1)) / (f + k1 * lengthNorm);
		}
		return sum;
	}

	/**
	 * 对文本分词：小写化 + 非字母数字分割 + 长度过滤。
	 *
	 * @param text 原始文本
	 * @return token 列表
	 */
	private List<String> tokenize(String text) {
		if (text == null || text.isEmpty()) {
			return new ArrayList<>(0);
		}
		String[] raw = text.toLowerCase().split("[^a-z0-9]+");
		List<String> tokens = new ArrayList<>(raw.length);
		for (String token : raw) {
			if (token.length() >= minTokenLength) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	/**
	 * 返回不可变的文档副本。
	 *
	 * @return 文档列表
	 */
	public List<Document> documents() {
		return documents;
	}

	/**
	 * 带 BM25 得分的检索结果。
	 *
	 * @param document 命中文档
	 * @param score BM25 得分
	 */
	public record Scored(Document document, double score) {
	}
}
