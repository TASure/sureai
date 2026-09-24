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

package com.sure.ai.client.resilience;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.client.resilience.CircuitBreaker.Snapshot;
import com.sure.ai.client.resilience.CircuitBreaker.State;

/**
 * {@link CircuitBreaker} 单元测试：纯内存状态机，零网络。
 *
 * @author sureai
 * @since 1.1.0
 */
public class CircuitBreakerTest {

	/** 小超时便于测试 OPEN→HALF_OPEN 过渡。 */
	private static final long SHORT_TIMEOUT_MS = 200L;

	/** CLOSED 状态 allowRequest 总返回 true。 */
	@Test
	public void testClosedAllowsRequests() {
		CircuitBreaker cb = CircuitBreaker.builder().build();
		assertTrue(cb.allowRequest());
		assertTrue(cb.allowRequest());
		cb.onSuccess();
		cb.onSuccess();
		assertEquals(State.CLOSED, cb.state());
	}

	/** 连续失败达到阈值 → state()==OPEN。 */
	@Test
	public void testFailureThresholdOpens() {
		CircuitBreaker cb = CircuitBreaker.builder().failureThreshold(3).windowSize(5).build();
		cb.onFailure();
		assertEquals(State.CLOSED, cb.state());
		cb.onFailure();
		assertEquals(State.CLOSED, cb.state());
		cb.onFailure();
		assertEquals(State.OPEN, cb.state());
	}

	/** OPEN 期间 allowRequest 返回 false（快速失败）。 */
	@Test
	public void testOpenRejectsRequests() {
		CircuitBreaker cb = CircuitBreaker.builder().failureThreshold(2).build();
		cb.onFailure();
		cb.onFailure();
		assertEquals(State.OPEN, cb.state());
		assertFalse(cb.allowRequest());
		assertFalse(cb.allowRequest());
	}

	/** OPEN 超时后 allowRequest 返回 true（转 HALF_OPEN）。 */
	@Test
	public void testOpenTimeoutTransitionsToHalfOpen() throws InterruptedException {
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(2).openTimeoutMs(SHORT_TIMEOUT_MS).build();
		cb.onFailure();
		cb.onFailure();
		assertEquals(State.OPEN, cb.state());
		assertFalse(cb.allowRequest());
		Thread.sleep(SHORT_TIMEOUT_MS + 100L);
		// 超时后首个探测放行
		assertTrue(cb.allowRequest());
		// 默认 halfOpenPermittedCalls=1，配额用完后拒绝
		assertFalse(cb.allowRequest());
	}

	/** HALF_OPEN 探测成功 → CLOSED。 */
	@Test
	public void testHalfOpenSuccessCloses() throws InterruptedException {
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(2).openTimeoutMs(SHORT_TIMEOUT_MS).build();
		cb.onFailure();
		cb.onFailure();
		assertEquals(State.OPEN, cb.state());
		Thread.sleep(SHORT_TIMEOUT_MS + 100L);
		assertTrue(cb.allowRequest());
		cb.onSuccess();
		assertEquals(State.CLOSED, cb.state());
		assertTrue(cb.allowRequest());
	}

	/** HALF_OPEN 探测失败 → 回 OPEN。 */
	@Test
	public void testHalfOpenFailureReopens() throws InterruptedException {
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(2).openTimeoutMs(SHORT_TIMEOUT_MS).build();
		cb.onFailure();
		cb.onFailure();
		Thread.sleep(SHORT_TIMEOUT_MS + 100L);
		assertTrue(cb.allowRequest());
		cb.onFailure();
		assertEquals(State.OPEN, cb.state());
		// 立即快速失败（新的 openedAt 已重置，需再等一个超时周期）
		assertFalse(cb.allowRequest());
	}

	/** snapshot 返回不可变快照，每次调用为新对象。 */
	@Test
	public void testSnapshotImmutable() {
		CircuitBreaker cb = CircuitBreaker.builder().build();
		Snapshot s1 = cb.snapshot();
		assertEquals(State.CLOSED, s1.state());
		assertEquals(0, s1.totalCalls());
		cb.onSuccess();
		cb.onFailure();
		Snapshot s2 = cb.snapshot();
		assertNotSame(s1, s2);
		assertEquals(2L, s2.totalCalls());
		assertEquals(1, s2.failureCount());
		assertEquals(1, s2.successCount());
		// record 组件只读，无 setter（编译期保证）
		assertEquals(State.CLOSED, s2.state());
	}

	/** 滑动窗口：成功把旧失败挤出窗口，可避免误 OPEN。 */
	@Test
	public void testSlidingWindowEvictsOldFailures() {
		// windowSize=3, threshold=2：S,F,S,F → 窗口 [F,S,F]，失败=2 应 OPEN
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(2).windowSize(3).build();
		cb.onSuccess(); // [S]
		assertEquals(State.CLOSED, cb.state());
		cb.onFailure(); // [S,F]
		assertEquals(State.CLOSED, cb.state());
		cb.onSuccess(); // [S,F,S]
		assertEquals(State.CLOSED, cb.state());
		cb.onFailure(); // 淘汰最早 S → [F,S,F]，失败=2 → OPEN
		assertEquals(State.OPEN, cb.state());
	}

	/** OPEN 惰性状态：state() 在超时后报告 HALF_OPEN 而不真正转移。 */
	@Test
	public void testStateLazyOpenTimeout() throws InterruptedException {
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(1).openTimeoutMs(SHORT_TIMEOUT_MS).build();
		cb.onFailure();
		assertEquals(State.OPEN, cb.state());
		Thread.sleep(SHORT_TIMEOUT_MS + 100L);
		// state() 惰性报告 HALF_OPEN
		assertEquals(State.HALF_OPEN, cb.state());
	}
}
