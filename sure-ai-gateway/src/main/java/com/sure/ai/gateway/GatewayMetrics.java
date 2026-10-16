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
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网关级指标聚合器：面向网关层事件（请求计数、路由成败、熔断、成本）的进程内统计。
 *
 * <p>与 core 的 {@code com.sure.ai.client.observability.AiMetrics} /
 * {@code MetricsCollector} 体系同构：零第三方依赖、进程内聚合、
 * 线程安全、{@link #snapshot()} 返回不可变快照、{@link #reset()} 清零。
 * 区别在于本类面向网关事件（路由 / 熔断 / 成本估算），而非 HTTP 路径回调；
 * 生产环境可由 sure-ai-micrometer 模块把快照桥接到 Micrometer。</p>
 *
 * <p>计数使用 {@link AtomicLong} 累加；延迟样本有界（最多 {@value #MAX_SAMPLES} 条最近样本，
 * 超出淘汰最旧），{@link #snapshot()} 时对样本副本排序计算 avg / p50 / p95 / p99。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public final class GatewayMetrics {

	/** 延迟样本上限（超出淘汰最旧）。 */
	public static final int MAX_SAMPLES = 4096;

	/** 请求总数。 */
	private final AtomicLong totalRequests = new AtomicLong();

	/** 成功数。 */
	private final AtomicLong successCount = new AtomicLong();

	/** 失败数。 */
	private final AtomicLong failureCount = new AtomicLong();

	/** 熔断触发次数（CLOSED → OPEN）。 */
	private final AtomicLong circuitBreakerTrips = new AtomicLong();

	/** 熔断快速失败拒绝次数（OPEN 状态下的拒绝）。 */
	private final AtomicLong circuitBreakerRejections = new AtomicLong();

	/** 累计估算成本（USD）。 */
	private final AtomicReference<Double> estimatedCostUsd = new AtomicReference<>(0.0d);

	/** 延迟样本队列（成功 + 失败都记），由 synchronized 守护线程安全。 */
	private final Deque<Long> latencies = new ArrayDeque<>();

	/** 请求开始回调：请求总数 +1。 */
	public void onRequestStart(String method, String model) {
		this.totalRequests.incrementAndGet();
	}

	/** 请求成功回调：成功数 +1 并记录耗时。 */
	public void onRequestSuccess(String method, String model, long latencyMs) {
		this.successCount.incrementAndGet();
		recordLatency(latencyMs);
	}

	/** 请求失败回调：失败数 +1 并记录耗时（失败也计入延迟分布）。 */
	public void onRequestFailure(String method, String model, long latencyMs) {
		this.failureCount.incrementAndGet();
		recordLatency(latencyMs);
	}

	/** 熔断从 CLOSED 转为 OPEN 时回调：熔断触发数 +1。 */
	public void onCircuitTrip(String platform, String instanceId) {
		this.circuitBreakerTrips.incrementAndGet();
	}

	/** OPEN 状态下快速失败拒绝时回调：熔断拒绝数 +1。 */
	public void onCircuitReject(String platform, String instanceId) {
		this.circuitBreakerRejections.incrementAndGet();
	}

	/** 成本估算入账（累加，线程安全）。 */
	public void recordCost(double costUsd) {
		this.estimatedCostUsd.updateAndGet(v -> v + costUsd);
	}

	/**
	 * 拍取当前指标的不可变快照。
	 *
	 * <p>对延迟样本做一次拷贝后在副本上排序计算百分位；快照为 primitive 字段，
	 * 拍取后继续调用本类方法不会影响已返回的快照。空样本时 avg 与各百分位均为 0。</p>
	 *
	 * @return 不可变快照
	 */
	public Snapshot snapshot() {
		List<Long> copy;
		synchronized (this.latencies) {
			copy = new ArrayList<>(this.latencies);
		}
		int n = copy.size();
		double avg = 0.0d;
		double p50 = 0.0d;
		double p95 = 0.0d;
		double p99 = 0.0d;
		if (n > 0) {
			long[] sorted = new long[n];
			long sum = 0L;
			for (int i = 0; i < n; i++) {
				long v = copy.get(i);
				sorted[i] = v;
				sum += v;
			}
			Arrays.sort(sorted);
			avg = (double) sum / n;
			p50 = percentile(sorted, 0.50d);
			p95 = percentile(sorted, 0.95d);
			p99 = percentile(sorted, 0.99d);
		}
		return new Snapshot(
			this.totalRequests.get(),
			this.successCount.get(),
			this.failureCount.get(),
			this.circuitBreakerTrips.get(),
			this.circuitBreakerRejections.get(),
			avg, p50, p95, p99,
			this.estimatedCostUsd.get());
	}

	/** 清零所有计数、成本与延迟样本。 */
	public void reset() {
		this.totalRequests.set(0L);
		this.successCount.set(0L);
		this.failureCount.set(0L);
		this.circuitBreakerTrips.set(0L);
		this.circuitBreakerRejections.set(0L);
		this.estimatedCostUsd.set(0.0d);
		synchronized (this.latencies) {
			this.latencies.clear();
		}
	}

	private void recordLatency(long latencyMs) {
		synchronized (this.latencies) {
			this.latencies.addLast(latencyMs);
			while (this.latencies.size() > MAX_SAMPLES) {
				this.latencies.removeFirst();
			}
		}
	}

	private static double percentile(long[] sorted, double q) {
		int n = sorted.length;
		int idx = (int) Math.ceil(q * n) - 1;
		if (idx < 0) {
			idx = 0;
		}
		if (idx > n - 1) {
			idx = n - 1;
		}
		return sorted[idx];
	}

	/**
	 * 网关指标不可变快照。
	 *
	 * @param totalRequests            请求总数
	 * @param successCount             成功数
	 * @param failureCount             失败数
	 * @param circuitBreakerTrips      熔断触发次数
	 * @param circuitBreakerRejections 熔断拒绝次数
	 * @param avgLatencyMs             平均延迟（ms），无样本为 0
	 * @param p50Ms                    p50 延迟（ms），无样本为 0
	 * @param p95Ms                    p95 延迟（ms），无样本为 0
	 * @param p99Ms                    p99 延迟（ms），无样本为 0
	 * @param estimatedCostUsd         累计估算成本（USD）
	 */
	public record Snapshot(
		long totalRequests,
		long successCount,
		long failureCount,
		long circuitBreakerTrips,
		long circuitBreakerRejections,
		double avgLatencyMs,
		double p50Ms,
		double p95Ms,
		double p99Ms,
		double estimatedCostUsd) {
	}
}
