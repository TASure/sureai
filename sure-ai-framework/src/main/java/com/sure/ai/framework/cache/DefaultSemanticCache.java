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

package com.sure.ai.framework.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.cache.CacheStore;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.tool.lang.Assert;

/**
 * {@link SemanticCache} 的默认实现：内存向量索引 + 可插拔 {@link CacheStore} 载荷后端。
 *
 * <p><b>命中流程</b>：{@code query} → {@link EmbeddingClient} 编码为向量 → 与索引内未过期条目
 * 逐条计算余弦相似度 → 取最高相似度者；其相似度 ≥ 阈值且后端仍能取到载荷即命中返回，
 * 否则 miss。过期条目在扫描时惰性剔除。</p>
 *
 * <p><b>键形态</b>：不再使用 {@code ChatCacheKey}，条目以 {@code sha256(query)} 为 entryId，
 * 既作为索引键，也作为载荷在后端 {@link CacheStore} 中的存储键。</p>
 *
 * <p>零第三方依赖；索引为受控规模的顺序线性扫描，文档已注明不适合大规模向量检索。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class DefaultSemanticCache implements SemanticCache {

	private final EmbeddingClient embedder;
	private final double threshold;
	private final CacheStore store;
	private final int maxEntries;
	private final long defaultTtlMillis;
	private final String model;

	/** 内存向量索引：entryId → 条目（accessOrder=true 以支持 LRU 淘汰）。 */
	private final LinkedHashMap<String, Entry> index;

	DefaultSemanticCache(EmbeddingClient embedder, double threshold, CacheStore store,
			int maxEntries, long defaultTtlMillis, String model) {
		this.embedder = embedder;
		this.threshold = threshold;
		this.store = store;
		this.maxEntries = maxEntries;
		this.defaultTtlMillis = defaultTtlMillis;
		this.model = model;
		this.index = new LinkedHashMap<>(16, 0.75f, true);
	}

	@Override
	public ChatResponse get(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		float[] queryVec = embed(query);
		if (queryVec == null || queryVec.length == 0) {
			return null;
		}
		synchronized (this) {
			String bestId = null;
			double bestScore = -1.0;
			long now = System.currentTimeMillis();
			for (Map.Entry<String, Entry> e : this.index.entrySet()) {
				Entry entry = e.getValue();
				if (now >= entry.expireAtMillis) {
					this.index.remove(e.getKey());
					this.store.remove(e.getKey());
					continue;
				}
				double score = cosine(queryVec, entry.embedding);
				if (score >= this.threshold && score > bestScore) {
					bestScore = score;
					bestId = e.getKey();
				}
			}
			if (bestId == null) {
				return null;
			}
			ChatResponse hit = this.store.get(bestId);
			if (hit == null) {
				// 载荷已在后端过期被清理，同步剔除索引
				this.index.remove(bestId);
			}
			return hit;
		}
	}

	@Override
	public void put(String query, ChatResponse response, long ttlMillis) {
		Assert.notNull(query, "query 不能为 null");
		Assert.notNull(response, "response 不能为 null");
		float[] vec = embed(query);
		Assert.isTrue(vec != null && vec.length > 0,
			"无法为 query 生成 embedding，拒绝写入语义缓存");
		long ttl = ttlMillis > 0 ? ttlMillis : this.defaultTtlMillis;
		String entryId = entryId(query);
		synchronized (this) {
			this.index.put(entryId, new Entry(query, vec, System.currentTimeMillis() + ttl));
			while (this.index.size() > this.maxEntries) {
				String eldest = this.index.keySet().iterator().next();
				this.index.remove(eldest);
				this.store.remove(eldest);
			}
			this.store.put(entryId, response, ttl);
		}
	}

	@Override
	public void remove(String query) {
		if (query == null || query.isBlank()) {
			return;
		}
		String entryId = entryId(query);
		synchronized (this) {
			this.index.remove(entryId);
			this.store.remove(entryId);
		}
	}

	@Override
	public void clear() {
		synchronized (this) {
			this.index.clear();
			this.store.clear();
		}
	}

	/**
	 * 当前内存索引条目数（物理条目；过期条目仅在访问时惰性删除）。
	 *
	 * @return 索引条目数
	 */
	public synchronized int size() {
		return this.index.size();
	}

	/** 对查询文本编码为向量；失败 / 空结果返回 {@code null}。 */
	private float[] embed(String query) {
		EmbeddingResponse resp = this.embedder.embed(new EmbeddingRequest(this.model, List.of(query)));
		if (resp == null || resp.embeddings().isEmpty()) {
			return null;
		}
		return resp.embeddings().get(0);
	}

	/** 两个等长向量的余弦相似度；任一向量模为 0 时返回 0。 */
	private static double cosine(float[] a, float[] b) {
		int len = Math.min(a.length, b.length);
		double dot = 0.0;
		double normA = 0.0;
		double normB = 0.0;
		for (int i = 0; i < len; i++) {
			dot += (double) a[i] * b[i];
			normA += (double) a[i] * a[i];
			normB += (double) b[i] * b[i];
		}
		if (normA == 0.0 || normB == 0.0) {
			return 0.0;
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}

	/** 以 sha256(query) 作为稳定 entryId。 */
	private static String entryId(String query) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(query.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	/** 内存索引条目：查询原文 + 向量 + 绝对过期时间戳。 */
	private static final class Entry {
		private final String query;
		private final float[] embedding;
		private final long expireAtMillis;

		Entry(String query, float[] embedding, long expireAtMillis) {
			this.query = query;
			this.embedding = embedding;
			this.expireAtMillis = expireAtMillis;
		}
	}
}
