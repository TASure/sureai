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

import com.sure.ai.model.ChatResponse;

/**
 * 对话响应缓存存储 SPI。
 *
 * <p>key 由 {@link ChatCacheKey#of} 归一化请求后计算得到；实现方只需负责键值对的
 * 持久化与过期策略。{@code get} 返回 {@code null} 表示未命中或已过期。</p>
 *
 * <p>内置提供纯 JDK 实现 {@link LruCacheStore}；如需 Redis / Caffeine 等外部存储，
 * 实现本接口并通过 {@code AiConfig.Builder#cacheStore(CacheStore)} 注入即可，
 * core 不强制依赖任何第三方缓存库。</p>
 *
 * <p>实现需自行保证线程安全。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public interface CacheStore {

	/**
	 * 读取缓存。
	 *
	 * @param key 缓存键（{@link ChatCacheKey#of} 产出）
	 * @return 命中的响应；未命中或已过期返回 {@code null}
	 */
	ChatResponse get(String key);

	/**
	 * 写入缓存。
	 *
	 * <p>实现应记录写入时间，并在 {@code ttlMillis} 毫秒后过期；
	 * 当 {@code ttlMillis <= 0} 时使用实现自带的默认 TTL。</p>
	 *
	 * @param key        缓存键
	 * @param response    成功的对话响应（错误响应不应写入）
	 * @param ttlMillis   存活毫秒数，&lt;=0 使用 store 默认 TTL
	 */
	void put(String key, ChatResponse response, long ttlMillis);

	/**
	 * 删除单条缓存。
	 *
	 * @param key 缓存键
	 */
	void remove(String key);

	/** 清空全部缓存。 */
	void clear();
}
