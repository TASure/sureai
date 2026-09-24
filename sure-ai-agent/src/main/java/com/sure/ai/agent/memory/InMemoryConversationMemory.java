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

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.sure.ai.model.ChatMessage;
import com.sure.tool.lang.Assert;

/**
 * 基于内存的环形窗口会话记忆。
 *
 * <p>内部使用 {@link LinkedList} 维护时间正序消息；当条数超过
 * {@code maxEntries} 时自动淘汰最早的消息（环形窗口）。</p>
 *
 * <p>线程安全：所有读写方法均以 {@code synchronized} 同步，可在多线程间共享。
 * {@link #history()} 返回不可变快照，调用方修改不会影响内部状态。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class InMemoryConversationMemory implements ConversationMemory {

	/** 默认窗口大小。 */
	public static final int DEFAULT_MAX_ENTRIES = 20;

	private final int maxEntries;

	/** 时间正序消息队列（头 = 最早，尾 = 最新）。 */
	private final LinkedList<ChatMessage> deque = new LinkedList<>();

	/**
	 * 用默认窗口大小（{@value #DEFAULT_MAX_ENTRIES}）构造。
	 */
	public InMemoryConversationMemory() {
		this(DEFAULT_MAX_ENTRIES);
	}

	/**
	 * 构造环形窗口记忆。
	 *
	 * @param maxEntries 窗口最大条数（≥1）
	 */
	public InMemoryConversationMemory(int maxEntries) {
		Assert.isTrue(maxEntries >= 1, "maxEntries must be >= 1");
		this.maxEntries = maxEntries;
	}

	@Override
	public synchronized void add(ChatMessage message) {
		Assert.notNull(message, "message must not be null");
		this.deque.addLast(message);
		while (this.deque.size() > this.maxEntries) {
			this.deque.removeFirst();
		}
	}

	@Override
	public synchronized List<ChatMessage> history() {
		return List.copyOf(this.deque);
	}

	@Override
	public synchronized void clear() {
		this.deque.clear();
	}

	@Override
	public synchronized int size() {
		return this.deque.size();
	}

	@Override
	public synchronized String toString() {
		return new ArrayList<>(this.deque).toString();
	}
}
