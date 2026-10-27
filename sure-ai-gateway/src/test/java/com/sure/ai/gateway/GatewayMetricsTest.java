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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link GatewayMetrics} 单元测试。
 *
 * @author sureai
 * @since 2.7.0
 */
public class GatewayMetricsTest {

	/** start / success / failure 回调分别累计请求总数与成败计数。 */
	@Test
	public void countsAreAccumulated() {
		GatewayMetrics m = new GatewayMetrics();
		m.onRequestStart("chat", "gpt");
		m.onRequestStart("chat", "gpt");
		m.onRequestSuccess("chat", "gpt", 10L);
		m.onRequestSuccess("chat", "gpt", 20L);
		m.onRequestFailure("chat", "gpt", 30L);

		GatewayMetrics.Snapshot s = m.snapshot();
		assertEquals(2L, s.totalRequests());
		assertEquals(2L, s.successCount());
		assertEquals(1L, s.failureCount());
	}

	/** 熔断触发与熔断拒绝分别累计到独立计数器。 */
	@Test
	public void circuitBreakerCounters() {
		GatewayMetrics m = new GatewayMetrics();
		m.onCircuitTrip("openai", "i-1");
		m.onCircuitTrip("openai", "i-1");
		m.onCircuitReject("openai", "i-1");

		GatewayMetrics.Snapshot s = m.snapshot();
		assertEquals(2L, s.circuitBreakerTrips());
		assertEquals(1L, s.circuitBreakerRejections());
	}

	/** recordCost 多次调用累加累计成本。 */
	@Test
	public void costAccumulates() {
		GatewayMetrics m = new GatewayMetrics();
		m.recordCost(0.5d);
		m.recordCost(0.25d);

		assertEquals(0.75d, m.snapshot().estimatedCostUsd(), 1e-9);
	}

	/** 已知序列 1..100 的分位数：p50=50、p95=95、p99=99、avg=50.5。 */
	@Test
	public void percentilesOnKnownSequence() {
		GatewayMetrics m = new GatewayMetrics();
		for (int i = 1; i <= 100; i++) {
			m.onRequestSuccess("chat", "gpt", i);
		}
		GatewayMetrics.Snapshot s = m.snapshot();
		assertEquals(50.0d, s.p50Ms(), 1e-9);
		assertEquals(95.0d, s.p95Ms(), 1e-9);
		assertEquals(99.0d, s.p99Ms(), 1e-9);
		assertEquals(50.5d, s.avgLatencyMs(), 1e-9);
	}

	/** 空样本时 avg 与 p50/p95/p99 均为 0。 */
	@Test
	public void emptySnapshotPercentilesAreZero() {
		GatewayMetrics.Snapshot s = new GatewayMetrics().snapshot();
		assertEquals(0.0d, s.avgLatencyMs(), 1e-9);
		assertEquals(0.0d, s.p50Ms(), 1e-9);
		assertEquals(0.0d, s.p95Ms(), 1e-9);
		assertEquals(0.0d, s.p99Ms(), 1e-9);
	}

	/** 样本超过上限后淘汰最旧：2500 条 100ms 后再喂 2500 条 20000ms，p50 落在新样本区（证明旧样本被淘汰）。 */
	@Test
	public void sampleEvictionBeyondCap() {
		GatewayMetrics m = new GatewayMetrics();
		for (int i = 0; i < 2500; i++) {
			m.onRequestSuccess("chat", "gpt", 100L);
		}
		for (int i = 0; i < 2500; i++) {
			m.onRequestSuccess("chat", "gpt", 20000L);
		}
		GatewayMetrics.Snapshot s = m.snapshot();
		// 若不淘汰：5000 条中前 2500 为 100、p50 索引 2499 落在 100 区；淘汰后丢弃最旧 904 条 100，旧样本仅剩 1596 条，p50 索引 2047 已进入 20000 区
		assertEquals(20000.0d, s.p50Ms(), 1e-9);
		assertEquals(20000.0d, s.p95Ms(), 1e-9);
		assertEquals(20000.0d, s.p99Ms(), 1e-9);
	}

	/** reset 后所有计数、成本与百分位全部回到 0。 */
	@Test
	public void resetClearsEverything() {
		GatewayMetrics m = new GatewayMetrics();
		m.onRequestStart("chat", "gpt");
		m.onRequestSuccess("chat", "gpt", 100L);
		m.onCircuitTrip("p", "i");
		m.recordCost(1.0d);
		m.reset();

		GatewayMetrics.Snapshot s = m.snapshot();
		assertEquals(0L, s.totalRequests());
		assertEquals(0L, s.successCount());
		assertEquals(0L, s.failureCount());
		assertEquals(0L, s.circuitBreakerTrips());
		assertEquals(0L, s.circuitBreakerRejections());
		assertEquals(0.0d, s.estimatedCostUsd(), 1e-9);
		assertEquals(0.0d, s.p50Ms(), 1e-9);
	}

	/** 拍取快照后继续写入，已返回的快照字段不随之改变。 */
	@Test
	public void snapshotIsImmutableAfterwards() {
		GatewayMetrics m = new GatewayMetrics();
		m.onRequestStart("chat", "gpt");
		m.onRequestSuccess("chat", "gpt", 100L);
		m.recordCost(0.5d);
		GatewayMetrics.Snapshot frozen = m.snapshot();

		m.onRequestStart("chat", "gpt");
		m.onRequestFailure("chat", "gpt", 999L);
		m.recordCost(0.5d);

		assertEquals(1L, frozen.totalRequests());
		assertEquals(1L, frozen.successCount());
		assertEquals(0L, frozen.failureCount());
		assertEquals(100.0d, frozen.p50Ms(), 1e-9);
		assertEquals(0.5d, frozen.estimatedCostUsd(), 1e-9);
	}
}
