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

package com.sure.ai.client.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 内置零依赖的简单 {@link MetricsCollector} 实现。
 *
 * <p>线程安全（{@link AtomicLong} / {@link ConcurrentHashMap} / {@link AtomicLongArray}），
 * 进程内聚合：请求总数、成功/失败数、重试次数、各 HTTP 状态码计数、总耗时与平均耗时、
 * 耗时直方图（&lt;100ms / 100-500ms / 500-1000ms / 1-5s / &gt;5s 五桶）、
 * Token 用量累计。</p>
 *
 * <p>{@link #snapshot()} 返回不可变快照，{@link #reset()} 清零。</p>
 *
 * <p>注意：这是零依赖的进程内统计，适合调试与单机观测；
 * 生产环境建议接入 Micrometer/Prometheus（见 docs/observability.md）。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public class AiMetrics implements MetricsCollector {

	/** 耗时直方图桶数。 */
	private static final int BUCKETS = 5;

	private final AtomicLong totalRequests = new AtomicLong();
	private final AtomicLong successCount = new AtomicLong();
	private final AtomicLong failureCount = new AtomicLong();
	private final AtomicLong retryCount = new AtomicLong();
	private final ConcurrentHashMap<Integer, AtomicLong> statusCodeCounts = new ConcurrentHashMap<>();
	private final AtomicLong totalDurationMs = new AtomicLong();
	private final AtomicLongArray durationHistogram = new AtomicLongArray(BUCKETS);
	private final AtomicLong totalPromptTokens = new AtomicLong();
	private final AtomicLong totalCompletionTokens = new AtomicLong();
	private final AtomicLong totalTokens = new AtomicLong();

	@Override
	public void onRequestStart(String path) {
		this.totalRequests.incrementAndGet();
	}

	@Override
	public void onRequestSuccess(String path, int httpStatus, long durationMs) {
		this.successCount.incrementAndGet();
		recordStatus(httpStatus);
		recordDuration(durationMs);
	}

	@Override
	public void onRequestFailure(String path, int httpStatus, Exception exception, long durationMs) {
		this.failureCount.incrementAndGet();
		if (httpStatus > 0) {
			recordStatus(httpStatus);
		}
		recordDuration(durationMs);
	}

	@Override
	public void onRetry(String path, int attempt, int httpStatus) {
		this.retryCount.incrementAndGet();
	}

	@Override
	public void onTokenUsage(String model, long promptTokens, long completionTokens,
			long totalTokens) {
		this.totalPromptTokens.addAndGet(promptTokens);
		this.totalCompletionTokens.addAndGet(completionTokens);
		this.totalTokens.addAndGet(totalTokens);
	}

	/** 累加状态码计数。 */
	private void recordStatus(int httpStatus) {
		this.statusCodeCounts.computeIfAbsent(httpStatus, k -> new AtomicLong()).incrementAndGet();
	}

	/** 累加总耗时并落入直方图桶。 */
	private void recordDuration(long durationMs) {
		this.totalDurationMs.addAndGet(durationMs);
		this.durationHistogram.addAndGet(bucketIndex(durationMs), 1);
	}

	/** 按耗时选择直方图桶下标：0 &lt;100ms，1 100-500ms，2 500-1000ms，3 1-5s，4 &gt;5s。 */
	private static int bucketIndex(long ms) {
		if (ms < 100) {
			return 0;
		}
		if (ms < 500) {
			return 1;
		}
		if (ms < 1000) {
			return 2;
		}
		if (ms < 5000) {
			return 3;
		}
		return 4;
	}

	/** 清零所有计数。 */
	public void reset() {
		this.totalRequests.set(0);
		this.successCount.set(0);
		this.failureCount.set(0);
		this.retryCount.set(0);
		this.statusCodeCounts.clear();
		this.totalDurationMs.set(0);
		for (int i = 0; i < BUCKETS; i++) {
			this.durationHistogram.set(i, 0);
		}
		this.totalPromptTokens.set(0);
		this.totalCompletionTokens.set(0);
		this.totalTokens.set(0);
	}

	/**
	 * 不可变快照。
	 *
	 * @param totalRequests      请求总数
	 * @param successCount       成功数
	 * @param failureCount       失败数
	 * @param retryCount         重试总次数
	 * @param statusCodeCounts   各状态码计数（不可变）
	 * @param totalDurationMs    累计耗时（ms）
	 * @param avgDurationMs      平均耗时（ms）
	 * @param durationHistogram  耗时五桶计数（不可变副本）
	 * @param totalPromptTokens  累计提示 token
	 * @param totalCompletionTokens 累计补全 token
	 * @param totalTokens        累计总 token
	 */
	public record Snapshot(long totalRequests, long successCount, long failureCount,
			long retryCount, Map<Integer, Long> statusCodeCounts, long totalDurationMs,
			double avgDurationMs, long[] durationHistogram, long totalPromptTokens,
			long totalCompletionTokens, long totalTokens) {
	}

	/**
	 * 拍摄不可变快照（复制当前值，后续内部变化不影响快照）。
	 *
	 * @return 快照
	 */
	public Snapshot snapshot() {
		long requests = this.totalRequests.get();
		long totalDur = this.totalDurationMs.get();
		Map<Integer, Long> status = new java.util.TreeMap<>();
		for (Map.Entry<Integer, AtomicLong> e : this.statusCodeCounts.entrySet()) {
			status.put(e.getKey(), e.getValue().get());
		}
		long[] hist = new long[BUCKETS];
		for (int i = 0; i < BUCKETS; i++) {
			hist[i] = this.durationHistogram.get(i);
		}
		double avg = requests == 0 ? 0.0 : (double) totalDur / (double) requests;
		return new Snapshot(requests, this.successCount.get(), this.failureCount.get(),
			this.retryCount.get(), Map.copyOf(status), totalDur, avg, hist,
			this.totalPromptTokens.get(), this.totalCompletionTokens.get(),
			this.totalTokens.get());
	}
}
