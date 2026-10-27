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

package com.sure.ai.gateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 客户端延迟观测：记录每个 (平台, 实例) 的历史延迟，供最低延迟路由使用。
 *
 * <p>采用滑动窗口：同时限制最多保留 {@value #MAX_SAMPLES} 条最近样本，
 * 且丢弃早于 {@value #WINDOW_MINUTES} 分钟的样本。线程安全。</p>
 *
 * <p>时间源可注入（包内构造器），便于在不真实睡眠的情况下测试窗口过期逻辑。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class LatencyTracker {

	/** 每个实例最多保留的样本数。 */
	public static final int MAX_SAMPLES = 100;

	/** 窗口长度（分钟）。 */
	public static final long WINDOW_MINUTES = 5L;

	/** 无历史数据时使用的默认平均延迟（ms），略高于真实值以降低冷启动被选中的概率。 */
	public static final double DEFAULT_LATENCY_MS = 10_000d;

	/** (platform|instanceId) → 样本队列 [timeMs, latencyMs]。 */
	private final Map<String, Deque<long[]>> samples = new ConcurrentHashMap<>();

	/** 时间源（ms epoch）。 */
	private final LongSupplier clock;

	/** 默认构造器，使用系统时钟。 */
	public LatencyTracker() {
		this(System::currentTimeMillis);
	}

	/**
	 * 测试用构造器，注入时间源。
	 *
	 * @param clock 毫秒时间源
	 */
	LatencyTracker(LongSupplier clock) {
		this.clock = clock;
	}

	/**
	 * 记录一次成功调用的延迟。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 * @param latencyMs  延迟毫秒
	 */
	public void record(String platform, String instanceId, long latencyMs) {
		String key = key(platform, instanceId);
		long now = this.clock.getAsLong();
		Deque<long[]> q = this.samples.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (q) {
			evictExpired(q, now);
			q.addLast(new long[] { now, latencyMs });
			while (q.size() > MAX_SAMPLES) {
				q.removeFirst();
			}
		}
	}

	/**
	 * 滑动窗口内的平均延迟（ms）。无样本返回 {@link #DEFAULT_LATENCY_MS}。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 * @return 平均延迟
	 */
	public double averageLatency(String platform, String instanceId) {
		String key = key(platform, instanceId);
		Deque<long[]> q = this.samples.get(key);
		if (q == null) {
			return DEFAULT_LATENCY_MS;
		}
		long now = this.clock.getAsLong();
		List<long[]> snapshot;
		synchronized (q) {
			evictExpired(q, now);
			snapshot = new ArrayList<>(q);
		}
		if (snapshot.isEmpty()) {
			return DEFAULT_LATENCY_MS;
		}
		long sum = 0L;
		for (long[] s : snapshot) {
			sum += s[1];
		}
		return (double) sum / snapshot.size();
	}

	/** 清空所有观测数据。 */
	public void reset() {
		this.samples.clear();
	}

	private void evictExpired(Deque<long[]> q, long now) {
		long cutoff = now - TimeUnit.MINUTES.toMillis(WINDOW_MINUTES);
		while (!q.isEmpty() && q.peekFirst()[0] < cutoff) {
			q.removeFirst();
		}
	}

	private static String key(String platform, String instanceId) {
		return platform + '|' + instanceId;
	}
}
