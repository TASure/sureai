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

import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.cache.CacheStore;
import com.sure.ai.client.cache.LruCacheStore;
import com.sure.tool.lang.Assert;

/**
 * {@link SemanticCache} 构建器。
 *
 * <p>仅 {@link #embedder(EmbeddingClient)} 必填；其余均有安全缺省值。示例：</p>
 *
 * <pre>{@code
 * SemanticCache cache = SemanticCache.builder()
 *         .embedder(embeddingClient)
 *         .threshold(0.85)
 *         .model("text-embedding-v1")
 *         .build();
 * }</pre>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class Builder {

	/** 默认相似度阈值。 */
	public static final double DEFAULT_THRESHOLD = 0.85;
	/** 默认索引容量（条目受控的顺序扫描规模上限）。 */
	public static final int DEFAULT_MAX_ENTRIES = 1000;
	/** 默认 TTL：10 分钟。 */
	public static final long DEFAULT_TTL_MILLIS = 10L * 60L * 1000L;

	private EmbeddingClient embedder;
	private double threshold = DEFAULT_THRESHOLD;
	private CacheStore store;
	private int maxEntries = DEFAULT_MAX_ENTRIES;
	private long defaultTtlMillis = DEFAULT_TTL_MILLIS;
	private String model;

	Builder() {
	}

	/**
	 * 设置向量客户端（必填）。任何 core {@link EmbeddingClient} 实现均可插拔。
	 *
	 * @param embedder 向量客户端
	 * @return this
	 */
	public Builder embedder(EmbeddingClient embedder) {
		this.embedder = embedder;
		return this;
	}

	/**
	 * 设置命中阈值：余弦相似度 ≥ 该值才视为命中，默认 {@value #DEFAULT_THRESHOLD}。
	 *
	 * @param threshold 阈值，取值 [0, 1]
	 * @return this
	 */
	public Builder threshold(double threshold) {
		Assert.isTrue(threshold >= 0.0 && threshold <= 1.0,
			"threshold 必须落在 [0,1]: " + threshold);
		this.threshold = threshold;
		return this;
	}

	/**
	 * 设置响应载荷的持久化后端（可选）。缺省为进程内 {@link LruCacheStore}；
	 * 传入 rag 的 {@code RedisCacheStore} 即可获得分布式共享缓存。
	 *
	 * @param store core 缓存存储 SPI 实现
	 * @return this
	 */
	public Builder store(CacheStore store) {
		this.store = store;
		return this;
	}

	/**
	 * 设置内存向量索引容量上限，默认 {@value #DEFAULT_MAX_ENTRIES}；
	 * 超出时按 LRU 淘汰最久未访问条目。
	 *
	 * @param maxEntries 容量（≥1）
	 * @return this
	 */
	public Builder maxEntries(int maxEntries) {
		Assert.isTrue(maxEntries >= 1, "maxEntries 必须 >= 1: " + maxEntries);
		this.maxEntries = maxEntries;
		return this;
	}

	/**
	 * 设置默认 TTL（{@code put} 的 {@code ttlMillis<=0} 时使用），默认 10 分钟。
	 *
	 * @param defaultTtlMillis 默认存活毫秒数（&gt;0）
	 * @return this
	 */
	public Builder defaultTtlMillis(long defaultTtlMillis) {
		Assert.isTrue(defaultTtlMillis > 0, "defaultTtlMillis 必须为正: " + defaultTtlMillis);
		this.defaultTtlMillis = defaultTtlMillis;
		return this;
	}

	/**
	 * 设置传给 {@link EmbeddingClient} 的模型名（可选；某些实现允许为 null）。
	 *
	 * @param model 向量模型名
	 * @return this
	 */
	public Builder model(String model) {
		this.model = model;
		return this;
	}

	/**
	 * 构建 {@link SemanticCache} 实例。
	 *
	 * @return 语义缓存
	 */
	public SemanticCache build() {
		Assert.notNull(this.embedder, "embedder 不能为空");
		CacheStore backing = this.store != null
			? this.store
			: new LruCacheStore(this.maxEntries, this.defaultTtlMillis);
		return new DefaultSemanticCache(this.embedder, this.threshold, backing,
			this.maxEntries, this.defaultTtlMillis, this.model);
	}
}
