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
import com.sure.ai.model.ChatResponse;

/**
 * 语义缓存：以「查询文本的向量相似度」而非「精确请求 hash」判定命中。
 *
 * <p>与 core 的精确缓存（{@link CacheStore} / {@link com.sure.ai.client.cache.ChatCacheKey}）
 * 互补：精确缓存要求请求逐字段一致；语义缓存把查询文本经 {@link EmbeddingClient} 编码为向量，
 * 与已存条目逐条计算余弦相似度，相似度达到阈值且未过期即视为命中，从而让「同义改写」的
 * 不同措辞复用同一条 {@link ChatResponse}。</p>
 *
 * <p><b>存储模型</b>：本接口的实现维护一份<b>内存向量索引</b>（查询原文 + 向量 + 过期时间戳，
 * 供相似度扫描），而 {@link ChatResponse} 载荷通过可插拔的 core {@link CacheStore} 持久化——
 * 缺省为进程内 {@code LruCacheStore}，亦可传入 rag 的 {@code RedisCacheStore} 实现分布式共享。
 * 语义比较在内存顺序进行，仅适合条目受控（千级以下）的规模；大规模场景需外接向量索引，
 * 本批不展开。</p>
 *
 * <p>所有方法实现需线程安全；{@code get} 返回 {@code null} 表示未命中、相似度不足或已过期。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public interface SemanticCache {

	/**
	 * 语义读取：对 {@code query} 编码后与已存条目做相似度匹配。
	 *
	 * @param query 查询文本（空白视为未命中）
	 * @return 命中的响应；相似度不足 / 已过期 / 无条目时返回 {@code null}
	 */
	ChatResponse get(String query);

	/**
	 * 写入语义缓存：对 {@code query} 编码入索引，并把响应载荷写入后端 {@link CacheStore}。
	 *
	 * @param query      查询文本（不可为空）
	 * @param response   成功的对话响应（不可为 {@code null}）
	 * @param ttlMillis  存活毫秒数，&lt;=0 使用构建器默认 TTL
	 */
	void put(String query, ChatResponse response, long ttlMillis);

	/**
	 * 按查询原文精确移除其索引条目与对应载荷。
	 *
	 * @param query 查询文本
	 */
	void remove(String query);

	/** 清空全部索引条目与后端载荷。 */
	void clear();

	/**
	 * 创建构建器。
	 *
	 * @return 构建器
	 */
	static Builder builder() {
		return new Builder();
	}
}
