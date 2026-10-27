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

package com.sure.ai.agent.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sure.ai.model.ChatMessage;
import org.junit.Test;

/**
 * {@link InMemoryConversationMemory} 单元测试。
 */
public class MemoryTest {

	@Test
	public void testAddAndHistory() {
		InMemoryConversationMemory memory = new InMemoryConversationMemory();
		memory.add(ChatMessage.user("你好"));
		memory.add(ChatMessage.assistant("你好，有什么可以帮你？"));

		List<ChatMessage> history = memory.history();
		assertEquals(2, history.size());
		assertEquals("user", history.get(0).role().name().toLowerCase());
		assertEquals("你好", history.get(0).content());
		assertEquals("assistant", history.get(1).role().name().toLowerCase());
		// history 返回不可变快照
		try {
			history.add(ChatMessage.user("x"));
			fail("history() should be unmodifiable");
		} catch (UnsupportedOperationException expected) {
			// pass
		}
	}

	@Test
	public void testWindowEviction() {
		InMemoryConversationMemory memory = new InMemoryConversationMemory(3);
		memory.add(ChatMessage.user("m1"));
		memory.add(ChatMessage.user("m2"));
		memory.add(ChatMessage.user("m3"));
		memory.add(ChatMessage.user("m4"));

		assertEquals(3, memory.size());
		List<ChatMessage> history = memory.history();
		assertEquals("m2", history.get(0).content());
		assertEquals("m3", history.get(1).content());
		assertEquals("m4", history.get(2).content());
	}

	@Test
	public void testClearAndSize() {
		InMemoryConversationMemory memory = new InMemoryConversationMemory();
		memory.add(ChatMessage.user("a"));
		memory.add(ChatMessage.assistant("b"));
		assertEquals(2, memory.size());
		memory.clear();
		assertEquals(0, memory.size());
		assertTrue(memory.history().isEmpty());
	}

	@Test
	public void testInMemoryThreadSafety() throws Exception {
		final int threads = 8;
		final int perThread = 200;
		InMemoryConversationMemory memory = new InMemoryConversationMemory(50);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++) {
			new Thread(() -> {
				try {
					start.await();
					for (int i = 0; i < perThread; i++) {
						memory.add(ChatMessage.user("msg-" + i));
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			}).start();
		}
		start.countDown();
		assertTrue(done.await(10, TimeUnit.SECONDS));

		// 窗口大小为 50，并发写入后不应超过窗口、不应抛异常
		assertTrue(memory.size() <= 50);
	}
}
