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

package com.sure.ai.rag.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.sure.tool.lang.Assert;

/**
 * 进程内轨迹存储：基于 {@link LinkedHashMap} 保持插入顺序。
 *
 * <p>线程安全：读写通过内部 {@link ReentrantLock} 串行化（与
 * {@code InMemoryVectorStore} 同款惯例），避免在暴露对象本身加锁。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class InMemoryTraceStore implements TraceStore {

	private final Map<String, RagTrace> traces = new LinkedHashMap<>();
	private final ReentrantLock lock = new ReentrantLock();

	@Override
	public void save(RagTrace trace) {
		Assert.notNull(trace, "trace 不能为 null");
		lock.lock();
		try {
			this.traces.put(trace.traceId(), trace);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public List<RagTrace> all() {
		lock.lock();
		try {
			return List.copyOf(this.traces.values());
		} finally {
			lock.unlock();
		}
	}

	@Override
	public RagTrace findById(String traceId) {
		if (traceId == null) {
			return null;
		}
		lock.lock();
		try {
			return this.traces.get(traceId);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public int size() {
		lock.lock();
		try {
			return this.traces.size();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public String toString() {
		lock.lock();
		try {
			return "InMemoryTraceStore{size=" + this.traces.size() + "}";
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 便捷构造空存储。
	 *
	 * @return 进程内存储
	 */
	public static InMemoryTraceStore create() {
		return new InMemoryTraceStore();
	}

	/**
	 * 从已有轨迹列表批量构建。
	 *
	 * @param traces 轨迹列表
	 * @return 进程内存储
	 */
	public static InMemoryTraceStore of(List<RagTrace> traces) {
		InMemoryTraceStore store = new InMemoryTraceStore();
		for (RagTrace trace : new ArrayList<>(traces)) {
			store.save(trace);
		}
		return store;
	}
}
