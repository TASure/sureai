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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.sure.ai.model.ChatMessage;
import org.junit.Test;

/**
 * 长期记忆子系统边界补充测试。
 *
 * <p>覆盖：自定义向量化器走向量检索、非向量存储降级最近 N 条、空查询、
 * 提取器阈值/空消息/system 跳过、内存存储 null 入参与零向量余弦、
 * 摘要器阈值与条目不变量。</p>
 */
public class LongTermMemoryEdgeTest {

	/** 固定两维向量向量化器。 */
	private static final MemoryEmbedder UNIT = text -> new float[] { 1.0f, 0.0f };

	/** 非 VectorMemoryStore 的简单存储，用于降级分支。 */
	private static final class PlainStore implements MemoryStore {
		private final Map<String, MemoryEntry> map = new HashMap<>();

		@Override
		public void put(MemoryEntry entry) {
			this.map.put(entry.id(), entry);
		}

		@Override
		public Optional<MemoryEntry> get(String id) {
			return Optional.ofNullable(this.map.get(id));
		}

		@Override
		public void delete(String id) {
			this.map.remove(id);
		}

		@Override
		public List<MemoryEntry> all() {
			return new ArrayList<>(this.map.values());
		}

		@Override
		public void clear() {
			this.map.clear();
		}
	}

	@Test
	public void testVectorRecallWithCustomEmbedder() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		LongTermMemory ltm = new LongTermMemory(store, null, UNIT, null);
		ltm.remember(List.of(ChatMessage.user("西安天气如何"),
				ChatMessage.assistant("晴")), "s1");
		List<MemoryEntry> recalled = ltm.recall("西安", 3);
		assertTrue(!recalled.isEmpty());
	}

	@Test
	public void testRecallBlankQueryReturnsEmpty() {
		LongTermMemory ltm = new LongTermMemory(null, null, null, null);
		assertTrue(ltm.recall("  ", 3).isEmpty());
	}

	@Test
	public void testPlainStoreFallsBackToRecent() {
		PlainStore store = new PlainStore();
		LongTermMemory ltm = new LongTermMemory(store, null, null, null);
		ltm.remember(List.of(ChatMessage.user("第一条用户问题"),
				ChatMessage.assistant("回答一")), "s");
		ltm.remember(List.of(ChatMessage.user("第二条用户问题"),
				ChatMessage.assistant("回答二")), "s");
		List<MemoryEntry> recalled = ltm.recall("任意", 1);
		assertEquals(1, recalled.size());
		ltm.clear();
		assertEquals(0, store.all().size());
	}

	@Test
	public void testGettersExposeComponents() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		LengthBasedSummarizer summarizer = new LengthBasedSummarizer(100);
		LongTermMemory ltm = new LongTermMemory(store, null, null, summarizer);
		assertEquals(store, ltm.store());
		assertEquals(summarizer, ltm.summarizer());
	}

	@Test
	public void testExtractorThresholdAndSkips() {
		DefaultMemoryExtractor extractor = new DefaultMemoryExtractor(3);
		// system 消息被跳过；短 user 被跳过；长 user + assistant 沉淀
		List<MemoryEntry> entries = extractor.extract(List.of(
				ChatMessage.system("系统提示"),
				ChatMessage.user("   "),
				ChatMessage.user("这是一段足够长的用户问题"),
				ChatMessage.assistant("这是助手结论")), "sess");
		assertTrue(entries.stream().anyMatch(e -> e.content().contains("用户说")));
		assertTrue(entries.stream().anyMatch(e -> e.content().contains("助手答")));
		// 空消息列表返回空
		assertTrue(extractor.extract(List.of(), "s").isEmpty());
	}

	@Test
	public void testExtractorRejectsBadThreshold() {
		assertThrows(IllegalArgumentException.class,
				() -> new DefaultMemoryExtractor(0));
	}

	@Test
	public void testStoreNullEntryAndZeroVector() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		assertThrows(IllegalArgumentException.class, () -> store.put(null));
		// 空向量检索 → 空
		assertTrue(store.search(new float[0], 3).isEmpty());
		store.put(MemoryEntry.of("正文", Map.of()));
		// 无向量条目在向量检索中被跳过（len mismatch → continue）
		assertTrue(store.search(new float[] { 1.0f }, 3).isEmpty());
		// searchByText 空白查询 → 空
		assertTrue(store.searchByText("  ", 3).isEmpty());
		// 向量长度不匹配 → continue
		store.put(MemoryEntry.of("正文二", Map.of())
			.withEmbedding(new float[] { 1.0f, 2.0f }));
		assertTrue(store.search(new float[] { 1.0f }, 3).isEmpty());
	}

	@Test
	public void testCosineZeroVectorReturnsZero() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		// 两个零向量点积为 0，长度为 0 → 直接返回 0.0
		MemoryEntry e = MemoryEntry.of("正文", Map.of())
			.withEmbedding(new float[] { 0.0f });
		store.put(e);
		// 零向量余弦直接返回 0.0，不抛异常
		assertEquals(1, store.search(new float[] { 0.0f }, 1).size());
	}

	@Test
	public void testSummarizerRejectsBadMaxChars() {
		assertThrows(IllegalArgumentException.class, () -> new LengthBasedSummarizer(0));
	}

	@Test
	public void testMemoryEntryBlankContentRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> MemoryEntry.of("  ", Map.of()));
	}
}
