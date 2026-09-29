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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sure.ai.model.ChatMessage;
import org.junit.Test;

/**
 * {@link com.sure.ai.agent.memory.longterm} 包单元测试。
 */
public class LongTermMemoryTest {

	private static MemoryEntry entry(String content, float[] embedding) {
		return new MemoryEntry(null, content, Map.of("role", "test"),
			System.currentTimeMillis(), embedding);
	}

	@Test
	public void testInMemoryStorePutGet() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		MemoryEntry e = MemoryEntry.of("用户喜欢美式咖啡", Map.of("sessionId", "s1"));
		store.put(e);
		Optional<MemoryEntry> got = store.get(e.id());
		assertTrue(got.isPresent());
		assertEquals("用户喜欢美式咖啡", got.get().content());
		assertEquals("s1", got.get().metadata().get("sessionId"));
	}

	@Test
	public void testInMemoryStoreDelete() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		MemoryEntry e = MemoryEntry.of("待删除", Map.of());
		store.put(e);
		assertEquals(1, store.all().size());
		store.delete(e.id());
		assertTrue(store.get(e.id()).isEmpty());
		assertEquals(0, store.all().size());
	}

	@Test
	public void testInMemoryStoreClear() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		store.put(MemoryEntry.of("a", Map.of()));
		store.put(MemoryEntry.of("b", Map.of()));
		store.clear();
		assertEquals(0, store.all().size());
	}

	@Test
	public void testVectorSearchByCosine() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		// 正交向量：query=[1,0] 与 v1=[1,0] 完全同向，与 v2=[0,1] 正交，与 v3=[0.9,0.1] 次之
		store.put(entry("v1", new float[] { 1f, 0f }));
		store.put(entry("v2", new float[] { 0f, 1f }));
		store.put(entry("v3", new float[] { 0.9f, 0.1f }));

		List<MemoryEntry> top2 = store.search(new float[] { 1f, 0f }, 2);
		assertEquals(2, top2.size());
		assertEquals("v1", top2.get(0).content());
		assertEquals("v3", top2.get(1).content());
	}

	@Test
	public void testSearchByText() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		store.put(MemoryEntry.of("西安今天晴 26 度", Map.of()));
		store.put(MemoryEntry.of("用户喜欢美式咖啡", Map.of()));
		store.put(MemoryEntry.of("西安明天可能下雨", Map.of()));

		List<MemoryEntry> hits = store.searchByText("西安", 5);
		assertEquals(2, hits.size());
		assertTrue(hits.stream().allMatch(e -> e.content().contains("西安")));
	}

	@Test
	public void testSearchByTextK() {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		store.put(MemoryEntry.of("alpha 西安", Map.of()));
		store.put(MemoryEntry.of("beta 西安", Map.of()));
		store.put(MemoryEntry.of("gamma 西安", Map.of()));
		assertEquals(2, store.searchByText("西安", 2).size());
	}

	@Test
	public void testExtractor() {
		DefaultMemoryExtractor extractor = new DefaultMemoryExtractor();
		List<ChatMessage> msgs = List.of(
			ChatMessage.system("你是助手"),
			ChatMessage.user("帮我记住：我喜欢喝美式"),
			ChatMessage.tool("call1", "工具结果"),
			ChatMessage.assistant("好的，已记住您喜欢美式咖啡"));
		List<MemoryEntry> entries = extractor.extract(msgs, "session-7");
		// 1 条 user + 1 条 assistant；system/tool 不沉淀
		assertEquals(2, entries.size());
		assertEquals("user", entries.get(0).metadata().get("role"));
		assertTrue(entries.get(0).content().contains("我喜欢喝美式"));
		assertEquals("assistant", entries.get(1).metadata().get("role"));
		assertEquals("session-7", entries.get(1).metadata().get("sessionId"));
	}

	@Test
	public void testExtractorFiltersShortUser() {
		DefaultMemoryExtractor extractor = new DefaultMemoryExtractor(5);
		List<ChatMessage> msgs = List.of(
			ChatMessage.user("短"),
			ChatMessage.user("这是一条足够长的用户指令"),
			ChatMessage.assistant("好"));
		List<MemoryEntry> entries = extractor.extract(msgs, null);
		// 短 user 被过滤；长 user + assistant
		assertEquals(2, entries.size());
	}

	@Test
	public void testSummarizerShortTextAsIs() {
		LengthBasedSummarizer s = new LengthBasedSummarizer(500);
		List<ChatMessage> msgs = List.of(ChatMessage.user("你好"), ChatMessage.assistant("在的"));
		assertEquals("你好\n在的", s.summarize(msgs));
	}

	@Test
	public void testSummarizerLongTruncates() {
		LengthBasedSummarizer s = new LengthBasedSummarizer(40);
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 100; i++) {
			longText.append("字");
		}
		List<ChatMessage> msgs = List.of(ChatMessage.user(longText.toString()));
		String result = s.summarize(msgs);
		assertTrue(result.length() <= 40);
		assertTrue(result.contains("截断"));
	}

	@Test
	public void testLongTermMemoryRememberRecall() {
		LongTermMemory ltm = new LongTermMemory(null, null, null, null);
		ltm.remember(List.of(
			ChatMessage.user("我对花生过敏"),
			ChatMessage.assistant("已记录您对花生过敏")), "session-1");
		assertEquals(2, ltm.all().size());

		// 文本匹配召回（查询词需为已存正文的子串）
		List<MemoryEntry> recalled = ltm.recall("花生过敏", 3);
		assertTrue(recalled.size() >= 1);
		assertTrue(recalled.get(0).content().contains("花生"));
	}

	@Test
	public void testLongTermMemoryForget() {
		LongTermMemory ltm = new LongTermMemory(null, null, null, null);
		ltm.remember(List.of(ChatMessage.user("要遗忘的记忆"),
			ChatMessage.assistant("答案")), "s");
		String id = ltm.all().get(0).id();
		ltm.forget(id);
		assertEquals(1, ltm.all().size());
	}

	@Test
	public void testConcurrentAccess() throws Exception {
		InMemoryMemoryStore store = new InMemoryMemoryStore();
		int threads = 8;
		int perThread = 200;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(threads);
		List<Exception> errors = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			final int tid = t;
			pool.submit(() -> {
				try {
					for (int i = 0; i < perThread; i++) {
						store.put(MemoryEntry.of("t" + tid + "-m" + i, Map.of()));
					}
					store.searchByText("t" + tid, 50);
				} catch (Exception e) {
					synchronized (errors) {
						errors.add(e);
					}
				} finally {
					done.countDown();
				}
			});
		}
		assertTrue(done.await(10, TimeUnit.SECONDS));
		pool.shutdownNow();
		assertTrue(errors.isEmpty());
		assertEquals(threads * perThread, store.all().size());
	}
}
