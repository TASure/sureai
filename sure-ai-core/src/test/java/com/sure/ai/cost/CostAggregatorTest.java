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

package com.sure.ai.cost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.model.TokenUsage;

/**
 * {@link CostAggregator} 单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class CostAggregatorTest {

	/** record 多次后 tenantSummary 返回正确的 totalCalls/tokens/cost。 */
	@Test
	public void testAggregatorRecordAndSummary() {
		CostAggregator agg = new CostAggregator();
		// t1: 3 calls
		agg.record("t1", "gpt-4o", TokenUsage.of(100, 50, 150), 0.001);
		agg.record("t1", "gpt-4o", TokenUsage.of(200, 100, 300), 0.002);
		agg.record("t1", "gpt-4o-mini", TokenUsage.of(50, 20, 70), 0.0001);
		// t2: 1 call
		agg.record("t2", "gpt-4o", TokenUsage.of(500, 200, 700), 0.005);

		CostSummary s1 = agg.tenantSummary("t1");
		assertEquals(3L, s1.totalCalls());
		assertEquals(350L, s1.totalPromptTokens());   // 100+200+50
		assertEquals(170L, s1.totalCompletionTokens()); // 50+100+20
		assertEquals(0.0031, s1.totalCost(), 1e-9);    // 0.001+0.002+0.0001
		// per-model breakdown
		assertEquals(450L, s1.tokensByModel().get("gpt-4o").longValue());   // (100+50)+(200+100)
		assertEquals(70L, s1.tokensByModel().get("gpt-4o-mini").longValue()); // 50+20
		assertEquals(0.003, s1.costByModel().get("gpt-4o"), 1e-9);
		assertEquals(0.0001, s1.costByModel().get("gpt-4o-mini"), 1e-9);

		CostSummary s2 = agg.tenantSummary("t2");
		assertEquals(1L, s2.totalCalls());
		assertEquals(500L, s2.totalPromptTokens());
		assertEquals(200L, s2.totalCompletionTokens());
		assertEquals(0.005, s2.totalCost(), 1e-9);
	}

	/** 按模型汇总正确（跨租户）。 */
	@Test
	public void testAggregatorModelSummary() {
		CostAggregator agg = new CostAggregator();
		agg.record("t1", "gpt-4o", TokenUsage.of(100, 50, 150), 0.001);
		agg.record("t2", "gpt-4o", TokenUsage.of(200, 100, 300), 0.002);
		agg.record("t1", "o3", TokenUsage.of(50, 50, 100), 0.01);

		CostSummary gpt4o = agg.modelSummary("gpt-4o");
		assertEquals(2L, gpt4o.totalCalls());
		assertEquals(300L, gpt4o.totalPromptTokens());
		assertEquals(150L, gpt4o.totalCompletionTokens());
		assertEquals(0.003, gpt4o.totalCost(), 1e-9);
		assertEquals(450L, gpt4o.tokensByModel().get("gpt-4o").longValue());
		assertEquals(0.003, gpt4o.costByModel().get("gpt-4o"), 1e-9);

		CostSummary o3 = agg.modelSummary("o3");
		assertEquals(1L, o3.totalCalls());
		assertEquals(0.01, o3.totalCost(), 1e-9);
	}

	/** summarySince 只统计时间窗内的记录。 */
	@Test
	public void testAggregatorTimeWindow() throws InterruptedException {
		CostAggregator agg = new CostAggregator();
		// 先记 2 条
		agg.record("t1", "gpt-4o", TokenUsage.of(100, 50, 150), 0.001);
		agg.record("t1", "gpt-4o", TokenUsage.of(200, 100, 300), 0.002);
		// 等待确保前 2 条时间戳早于 boundary
		Thread.sleep(50);
		// 记录分界时间戳
		long boundary = System.currentTimeMillis();
		// 再记 1 条（在 boundary 之后）
		agg.record("t2", "o3", TokenUsage.of(50, 50, 100), 0.01);

		// summarySince(boundary) 只应包含第 3 条
		CostSummary recent = agg.summarySince(boundary);
		assertEquals(1L, recent.totalCalls());
		assertEquals(50L, recent.totalPromptTokens());
		assertEquals(50L, recent.totalCompletionTokens());
		assertEquals(0.01, recent.totalCost(), 1e-9);
		assertEquals(1L, recent.tokensByModel().size());
		assertTrue(recent.tokensByModel().containsKey("o3"));

		// summarySince(0) 包含全部 3 条
		CostSummary all = agg.summarySince(0);
		assertEquals(3L, all.totalCalls());
		assertEquals(350L, all.totalPromptTokens());
		assertEquals(200L, all.totalCompletionTokens());
		assertEquals(0.013, all.totalCost(), 1e-9);
	}

	/** reset 后汇总清零。 */
	@Test
	public void testAggregatorReset() {
		CostAggregator agg = new CostAggregator();
		agg.record("t1", "gpt-4o", TokenUsage.of(100, 50, 150), 0.001);
		agg.record("t2", "o3", TokenUsage.of(50, 50, 100), 0.01);
		assertEquals(2L, agg.summarySince(0).totalCalls());

		agg.reset();
		assertEquals(0L, agg.summarySince(0).totalCalls());
		assertEquals(0L, agg.tenantSummary("t1").totalCalls());
		assertEquals(0L, agg.modelSummary("gpt-4o").totalCalls());
	}

	/** 多线程并发 record 不丢数据（100 线程 × 100 次 = 10000 条）。 */
	@Test
	public void testAggregatorThreadSafety() throws InterruptedException {
		CostAggregator agg = new CostAggregator();
		int threadCount = 100;
		int perThread = 100;
		Thread[] threads = new Thread[threadCount];
		for (int t = 0; t < threadCount; t++) {
			final int idx = t;
			threads[t] = new Thread(() -> {
				for (int i = 0; i < perThread; i++) {
					agg.record("tenant-" + (idx % 4), "gpt-4o",
							TokenUsage.of(100, 50, 150), 0.001);
				}
			});
		}
		for (Thread th : threads) {
			th.start();
		}
		for (Thread th : threads) {
			th.join();
		}
		CostSummary all = agg.summarySince(0);
		assertEquals(threadCount * perThread, all.totalCalls());
		assertEquals(threadCount * perThread * 100L, all.totalPromptTokens());
		assertEquals(threadCount * perThread * 50L, all.totalCompletionTokens());
		assertEquals(threadCount * perThread * 0.001, all.totalCost(), 1e-6);

		// 4 个租户，每个 2500 条
		for (int i = 0; i < 4; i++) {
			assertEquals(2500L, agg.tenantSummary("tenant-" + i).totalCalls());
		}
	}

	/** 空租户/空模型返回 EMPTY。 */
	@Test
	public void testAggregatorEmptyQueries() {
		CostAggregator agg = new CostAggregator();
		assertEquals(CostSummary.EMPTY, agg.tenantSummary("nobody"));
		assertEquals(CostSummary.EMPTY, agg.modelSummary("nobody"));
		assertEquals(0L, agg.summarySince(0).totalCalls());
	}
}
