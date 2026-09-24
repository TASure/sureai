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

package com.sure.ai.client.cache;

import java.util.LinkedHashMap;
import java.util.Map;

import com.sure.ai.model.ChatResponse;
import com.sure.tool.lang.Assert;

/**
 * 基于 JDK {@link LinkedHashMap}（accessOrder=true）的内置 LRU + TTL 缓存。
 *
 * <p>容量满时淘汰最久未访问（LRU）的条目；{@code get} 时惰性检查 TTL，
 * 过期条目立即删除并视为未命中。全部方法 {@code synchronized}，线程安全。</p>
 *
 * <p>零第三方依赖；如需 Redis 等外部存储，实现 {@link CacheStore} SPI 即可。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class LruCacheStore implements CacheStore {

	/** 默认容量。 */
	public static final int DEFAULT_CAPACITY = 1024;
	/** 默认 TTL：5 分钟。 */
	public static final long DEFAULT_TTL_MILLIS = 5L * 60L * 1000L;

	private final int capacity;
	private final long defaultTtlMillis;
	private final LinkedHashMap<String, CacheEntry> map;

	/**
	 * 默认构造：容量 1024，TTL 5 分钟。
	 */
	public LruCacheStore() {
		this(DEFAULT_CAPACITY, DEFAULT_TTL_MILLIS);
	}

	/**
	 * 指定容量，TTL 使用默认 5 分钟。
	 *
	 * @param capacity 容量上限（&gt;0）
	 */
	public LruCacheStore(int capacity) {
		this(capacity, DEFAULT_TTL_MILLIS);
	}

	/**
	 * 指定容量与默认 TTL。
	 *
	 * @param capacity         容量上限（&gt;0）
	 * @param defaultTtlMillis 默认存活毫秒数（&gt;0）
	 */
	public LruCacheStore(int capacity, long defaultTtlMillis) {
		Assert.isTrue(capacity > 0, "capacity must be positive: " + capacity);
		Assert.isTrue(defaultTtlMillis > 0, "defaultTtlMillis must be positive: " + defaultTtlMillis);
		this.capacity = capacity;
		this.defaultTtlMillis = defaultTtlMillis;
		this.map = new LinkedHashMap<String, CacheEntry>(capacity, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
				return super.size() > LruCacheStore.this.capacity;
			}
		};
	}

	@Override
	public synchronized ChatResponse get(String key) {
		CacheEntry entry = this.map.get(key);
		if (entry == null) {
			return null;
		}
		if (entry.isExpired()) {
			this.map.remove(key);
			return null;
		}
		return entry.response;
	}

	@Override
	public synchronized void put(String key, ChatResponse response, long ttlMillis) {
		long ttl = ttlMillis > 0 ? ttlMillis : this.defaultTtlMillis;
		this.map.put(key, new CacheEntry(response, System.currentTimeMillis(), ttl));
	}

	@Override
	public synchronized void remove(String key) {
		this.map.remove(key);
	}

	@Override
	public synchronized void clear() {
		this.map.clear();
	}

	/**
	 * 当前条目数（物理条目；过期条目仅在访问时惰性删除，不主动扫描）。
	 *
	 * @return 条目数
	 */
	public synchronized int size() {
		return this.map.size();
	}

	/** 缓存条目：响应 + 写入时间 + TTL。 */
	private static final class CacheEntry {
		private final ChatResponse response;
		private final long writeTime;
		private final long ttlMillis;

		CacheEntry(ChatResponse response, long writeTime, long ttlMillis) {
			this.response = response;
			this.writeTime = writeTime;
			this.ttlMillis = ttlMillis;
		}

		boolean isExpired() {
			return System.currentTimeMillis() - this.writeTime >= this.ttlMillis;
		}
	}
}
