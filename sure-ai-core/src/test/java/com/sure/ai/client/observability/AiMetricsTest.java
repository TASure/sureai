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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link AiMetrics} 单元测试：直接调用埋点方法验证聚合逻辑。
 *
 * @author sureai
 * @since 0.3.0
 */
public class AiMetricsTest {

	/** 计数：start/success/failure/retry。 */
	@Test
	public void testMetricsCounter() {
		AiMetrics m = new AiMetrics();
		m.onRequestStart("/a");
		m.onRequestSuccess("/a", 200, 10);
		m.onRequestStart("/b");
		m.onRequestFailure("/b", 500, null, 20);
		m.onRetry("/a", 1, 429);
		m.onRetry("/a", 2, 503);
		AiMetrics.Snapshot s = m.snapshot();
		assertEquals(2, s.totalRequests());
		assertEquals(1, s.successCount());
		assertEquals(1, s.failureCount());
		assertEquals(2, s.retryCount());
	}

	/** 状态码计数。 */
	@Test
	public void testStatusCodeCounts() {
		AiMetrics m = new AiMetrics();
		m.onRequestStart("/x");
		m.onRequestSuccess("/x", 200, 5);
		m.onRequestStart("/y");
		m.onRequestFailure("/y", 400, null, 5);
		m.onRequestStart("/z");
		m.onRequestFailure("/z", 500, null, 5);
		AiMetrics.Snapshot s = m.snapshot();
		assertEquals(1L, s.statusCodeCounts().get(200).longValue());
		assertEquals(1L, s.statusCodeCounts().get(400).longValue());
		assertEquals(1L, s.statusCodeCounts().get(500).longValue());
		// IO 异常 status=-1 不计入状态码
		m.onRequestFailure("/w", -1, new RuntimeException(), 5);
		assertFalse(m.snapshot().statusCodeCounts().containsKey(-1));
	}

	/** 耗时直方图落入正确桶。 */
	@Test
	public void testDurationHistogram() {
		AiMetrics m = new AiMetrics();
		m.onRequestStart("/a");
		m.onRequestSuccess("/a", 200, 50L);     // bucket0 <100
		m.onRequestStart("/b");
		m.onRequestSuccess("/b", 200, 300L);    // bucket1 100-500
		m.onRequestStart("/c");
		m.onRequestSuccess("/c", 200, 800L);    // bucket2 500-1000
		m.onRequestStart("/d");
		m.onRequestSuccess("/d", 200, 2000L);   // bucket3 1-5s
		m.onRequestStart("/e");
		m.onRequestSuccess("/e", 200, 9000L);   // bucket4 >5s
		long[] h = m.snapshot().durationHistogram();
		assertEquals(5, h.length);
		assertEquals(1L, h[0]);
		assertEquals(1L, h[1]);
		assertEquals(1L, h[2]);
		assertEquals(1L, h[3]);
		assertEquals(1L, h[4]);
		// 总耗时累计
		assertEquals(50L + 300L + 800L + 2000L + 9000L, m.snapshot().totalDurationMs());
	}

	/** Token 累计。 */
	@Test
	public void testTokenUsage() {
		AiMetrics m = new AiMetrics();
		m.onTokenUsage("gpt", 10, 5, 15);
		m.onTokenUsage("gpt", 20, 7, 27);
		AiMetrics.Snapshot s = m.snapshot();
		assertEquals(30L, s.totalPromptTokens());
		assertEquals(12L, s.totalCompletionTokens());
		assertEquals(42L, s.totalTokens());
	}

	/** snapshot 不可变：拍摄后修改内部不影响快照。 */
	@Test
	public void testSnapshotImmutable() {
		AiMetrics m = new AiMetrics();
		m.onRequestStart("/a");
		m.onRequestSuccess("/a", 200, 10);
		AiMetrics.Snapshot s = m.snapshot();
		long reqs = s.totalRequests();
		// 后续修改
		m.onRequestStart("/b");
		m.onRequestSuccess("/b", 200, 10);
		assertEquals(reqs, s.totalRequests());
		// statusCodeCounts 不可变
		assertThrowsUnsupported(s.statusCodeCounts());
		// durationHistogram 返回的是快照拷贝，改写它不影响内部状态
		long[] hist = s.durationHistogram();
		hist[0] = 999;
		assertEquals(2L, m.snapshot().durationHistogram()[0]);
	}

	/** 断言 Map 不可变（put 抛异常）。 */
	private static void assertThrowsUnsupported(Map<Integer, Long> map) {
		boolean thrown = false;
		try {
			map.put(999, 1L);
		} catch (UnsupportedOperationException ex) {
			thrown = true;
		}
		assertTrue("statusCodeCounts should be immutable", thrown);
	}

	/** reset 后全部归零。 */
	@Test
	public void testReset() {
		AiMetrics m = new AiMetrics();
		m.onRequestStart("/a");
		m.onRequestSuccess("/a", 200, 10);
		m.onRetry("/a", 1, 429);
		m.onTokenUsage("gpt", 1, 1, 2);
		m.reset();
		AiMetrics.Snapshot s = m.snapshot();
		assertEquals(0, s.totalRequests());
		assertEquals(0, s.successCount());
		assertEquals(0, s.failureCount());
		assertEquals(0, s.retryCount());
		assertTrue(s.statusCodeCounts().isEmpty());
		assertEquals(0, s.totalDurationMs());
		assertEquals(0.0, s.avgDurationMs(), 0.0);
		for (long v : s.durationHistogram()) {
			assertEquals(0L, v);
		}
		assertEquals(0, s.totalTokens());
	}

	/** 空指标快照 avg=0、不空。 */
	@Test
	public void testEmptySnapshot() {
		AiMetrics.Snapshot s = new AiMetrics().snapshot();
		assertNotNull(s);
		assertEquals(0.0, s.avgDurationMs(), 0.0);
		assertEquals(5, s.durationHistogram().length);
	}

	/** 多线程并发计数正确。 */
	@Test
	public void testThreadSafety() throws InterruptedException {
		AiMetrics m = new AiMetrics();
		int threads = 8;
		int perThread = 200;
		CountDownLatch latch = new CountDownLatch(threads);
		AtomicLong errors = new AtomicLong();
		for (int t = 0; t < threads; t++) {
			new Thread(() -> {
				try {
					for (int i = 0; i < perThread; i++) {
						m.onRequestStart("/p");
						m.onRequestSuccess("/p", 200, 1);
						m.onRetry("/p", 1, 429);
					}
				} catch (RuntimeException ex) {
					errors.incrementAndGet();
				} finally {
					latch.countDown();
				}
			}).start();
		}
		latch.await();
		assertEquals(0, errors.get());
		AiMetrics.Snapshot s = m.snapshot();
		assertEquals((long) threads * perThread, s.totalRequests());
		assertEquals((long) threads * perThread, s.successCount());
		assertEquals((long) threads * perThread, s.retryCount());
	}
}
