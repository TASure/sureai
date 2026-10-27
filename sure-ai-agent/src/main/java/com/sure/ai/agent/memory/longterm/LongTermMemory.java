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

package com.sure.ai.agent.memory.longterm;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.model.ChatMessage;

/**
 * 长期记忆门面：跨会话沉淀与召回。
 *
 * <p>组合四个可插拔组件：</p>
 * <ul>
 *   <li>{@link MemoryStore}：底层存储（默认 {@link InMemoryMemoryStore}）；</li>
 *   <li>{@link MemoryExtractor}：从对话提炼记忆（默认 {@link DefaultMemoryExtractor}）；</li>
 *   <li>{@link MemoryEmbedder}：文本向量化（默认 {@link NoopMemoryEmbedder}，走文本匹配）；</li>
 *   <li>{@link MemorySummarizer}：摘要（默认 {@link LengthBasedSummarizer}，当前门面直接存储提取结果，
 *       摘要器保留供高级用法/子类扩展）。</li>
 * </ul>
 *
 * <p>典型用法：编排器运行前 {@link #recall(String, int)} 取相关记忆注入上下文，
 * 运行后 {@link #remember(List, String)} 沉淀本轮对话。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class LongTermMemory {

	/** 默认召回条数。 */
	public static final int DEFAULT_RECALL_K = 3;

	/** 底层存储。 */
	private final MemoryStore store;

	/** 记忆提取器。 */
	private final MemoryExtractor extractor;

	/** 向量化器。 */
	private final MemoryEmbedder embedder;

	/** 摘要器。 */
	private final MemorySummarizer summarizer;

	/**
	 * 全参构造（任一组件传 null 即使用默认实现）。
	 *
	 * @param store      底层存储（null → 内存实现）
	 * @param extractor 记忆提取器（null → 默认提取器）
	 * @param embedder   向量化器（null → 空实现，走文本匹配）
	 * @param summarizer 摘要器（null → 长度截断实现）
	 */
	public LongTermMemory(MemoryStore store, MemoryExtractor extractor,
			MemoryEmbedder embedder, MemorySummarizer summarizer) {
		this.store = store == null ? new InMemoryMemoryStore() : store;
		this.extractor = extractor == null ? new DefaultMemoryExtractor() : extractor;
		this.embedder = embedder == null ? NoopMemoryEmbedder.instance() : embedder;
		this.summarizer = summarizer == null ? new LengthBasedSummarizer() : summarizer;
	}

	/**
	 * 从对话中提取记忆并存储。
	 *
	 * <p>提取后若向量化器可用，则为每条记忆计算向量再落盘。</p>
	 *
	 * @param messages  本轮对话消息（非 null）
	 * @param sessionId 会话标识（可 null，写入元数据）
	 */
	public void remember(List<ChatMessage> messages, String sessionId) {
		List<MemoryEntry> entries = this.extractor.extract(messages, sessionId);
		for (MemoryEntry e : entries) {
			float[] vec = this.embedder.embed(e.content());
			if (vec != null) {
				this.store.put(e.withEmbedding(vec));
			} else {
				this.store.put(e);
			}
		}
	}

	/**
	 * 按查询召回 Top-K 相关记忆。
	 *
	 * <p>有向量化结果时走向量相似度检索；否则降级为文本包含匹配。</p>
	 *
	 * @param query 查询文本（通常为用户当前问题）
	 * @param k     召回条数（&ge;1）
	 * @return 相关记忆列表（可能为空）
	 */
	public List<MemoryEntry> recall(String query, int k) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		float[] vec = this.embedder.embed(query);
		if (vec != null && this.store instanceof VectorMemoryStore vms) {
			return vms.search(vec, k);
		}
		if (this.store instanceof VectorMemoryStore vms) {
			return vms.searchByText(query, k);
		}
		// 纯键值存储不支持检索：退化为最近 N 条
		List<MemoryEntry> all = this.store.all();
		return all.size() <= k ? all : new ArrayList<>(all.subList(all.size() - k, all.size()));
	}

	/**
	 * 按 ID 遗忘一条记忆。
	 *
	 * @param id 条目 ID
	 */
	public void forget(String id) {
		this.store.delete(id);
	}

	/**
	 * 全部记忆。
	 *
	 * @return 条目列表快照
	 */
	public List<MemoryEntry> all() {
		return this.store.all();
	}

	/**
	 * 清空全部长期记忆。
	 */
	public void clear() {
		this.store.clear();
	}

	/**
	 * 当前存储（高级用法：直接操作或观测）。
	 *
	 * @return 底层存储
	 */
	public MemoryStore store() {
		return this.store;
	}

	/**
	 * 当前摘要器（高级用法/测试断言）。
	 *
	 * @return 摘要器
	 */
	public MemorySummarizer summarizer() {
		return this.summarizer;
	}
}
