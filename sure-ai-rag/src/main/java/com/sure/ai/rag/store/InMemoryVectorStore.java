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

package com.sure.ai.rag.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.model.Vector;
import com.sure.tool.lang.Assert;

/**
 * 进程内向量存储：余弦相似度 + 暴力线性检索。
 *
 * <p>适合中小规模知识库（万级以下条目）与本地开发调试。
 * 线程安全：写入与检索通过可重入锁串行化，保证并发下数据一致。
 * 后续可通过实现 {@link VectorStore} 接入外部向量库替换本实现。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class InMemoryVectorStore implements VectorStore {

	private final Map<String, Vector> vectors = new LinkedHashMap<>();
	private final ReentrantLock lock = new ReentrantLock();

	@Override
	public void add(Vector vector) {
		Assert.notNull(vector, "vector 不能为 null");
		Assert.isTrue(vector.embedding() != null && vector.embedding().length > 0,
				"vector 的嵌入向量不能为空");
		lock.lock();
		try {
			vectors.put(vector.id(), vector);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void addAll(List<Vector> batch) {
		Assert.notNull(batch, "batch 不能为 null");
		lock.lock();
		try {
			for (Vector vector : batch) {
				if (vector != null) {
					vectors.put(vector.id(), vector);
				}
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public boolean delete(String id) {
		Assert.notNull(id, "id 不能为 null");
		lock.lock();
		try {
			return vectors.remove(id) != null;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void clear() {
		lock.lock();
		try {
			vectors.clear();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public int size() {
		lock.lock();
		try {
			return vectors.size();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK) {
		return similaritySearch(queryEmbedding, topK, Double.NEGATIVE_INFINITY);
	}

	@Override
	public List<SimilaritySearchResult> similaritySearch(float[] queryEmbedding, int topK,
			double minScore) {
		Assert.notNull(queryEmbedding, "queryEmbedding 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		Assert.isTrue(queryEmbedding.length > 0, "queryEmbedding 不能为空");
		lock.lock();
		try {
			List<SimilaritySearchResult> results = new ArrayList<>(vectors.size());
			for (Vector vector : vectors.values()) {
				double score = cosineSimilarity(queryEmbedding, vector.embedding());
				if (score >= minScore) {
					results.add(new SimilaritySearchResult(vector.id(), vector.embedding(),
							vector.text(), vector.metadata(), score));
				}
			}
			results.sort(Comparator.comparingDouble(SimilaritySearchResult::score).reversed());
			if (results.size() > topK) {
				return new ArrayList<>(results.subList(0, topK));
			}
			return results;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 计算两个向量的余弦相似度。
	 *
	 * <p>任一向量的模为 0 时返回 0（视为无相似性），避免除零。</p>
	 *
	 * @param a 向量 a
	 * @param b 向量 b
	 * @return 余弦相似度，取值 [-1, 1]
	 */
	private double cosineSimilarity(float[] a, float[] b) {
		int len = Math.min(a.length, b.length);
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < len; i++) {
			dot += (double) a[i] * b[i];
			normA += (double) a[i] * a[i];
			normB += (double) b[i] * b[i];
		}
		if (normA == 0 || normB == 0) {
			return 0;
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}
}
