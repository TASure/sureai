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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import org.junit.Test;

import com.sure.ai.client.resilience.CircuitBreaker;
import com.sure.ai.client.resilience.CircuitBreaker.Snapshot;

/**
 * {@link CircuitBreakerRegistry} 单元测试：纯内存三态状态机，零网络。
 *
 * @author sureai
 * @since 2.7.0
 */
public class CircuitBreakerRegistryTest {

	/** 小超时便于测试 OPEN→HALF_OPEN 过渡。 */
	private static final long SHORT_TIMEOUT_MS = 200L;

	/** 连续失败达阈值后 allowRequest 返回 false（OPEN 快速失败）。 */
	@Test
	public void failuresOpenAndReject() {
		CircuitBreakerRegistry reg = new CircuitBreakerRegistry(
				CircuitBreakerConfig.builder().failureThreshold(2).windowSize(5).build());
		assertTrue(reg.allowRequest("openai", "i1"));
		reg.onFailure("openai", "i1");
		reg.onFailure("openai", "i1");
		// 断言点：OPEN 后快速失败
		assertFalse(reg.allowRequest("openai", "i1"));
		assertEquals(1, reg.size());
	}

	/** OPEN 冷却后探测放行，探测成功回 CLOSED。 */
	@Test
	public void coolDownHalfOpenProbeSuccessCloses() throws InterruptedException {
		CircuitBreakerRegistry reg = new CircuitBreakerRegistry(
				CircuitBreakerConfig.builder()
					.failureThreshold(2).openTimeoutMs(SHORT_TIMEOUT_MS).build());
		reg.onFailure("openai", "i1");
		reg.onFailure("openai", "i1");
		assertFalse(reg.allowRequest("openai", "i1"));
		Thread.sleep(SHORT_TIMEOUT_MS + 100L);
		// 断言点：冷却后首个探测放行
		assertTrue(reg.allowRequest("openai", "i1"));
		reg.onSuccess("openai", "i1");
		// 断言点：探测成功回 CLOSED，后续继续放行
		assertEquals(CircuitBreaker.State.CLOSED, reg.snapshot("openai", "i1").state());
		assertTrue(reg.allowRequest("openai", "i1"));
	}

	/** HALF_OPEN 探测失败回 OPEN 并重记 openedAt，立即快速失败。 */
	@Test
	public void halfOpenProbeFailureReopens() throws InterruptedException {
		CircuitBreakerRegistry reg = new CircuitBreakerRegistry(
				CircuitBreakerConfig.builder()
					.failureThreshold(2).openTimeoutMs(SHORT_TIMEOUT_MS).build());
		reg.onFailure("openai", "i1");
		reg.onFailure("openai", "i1");
		Thread.sleep(SHORT_TIMEOUT_MS + 100L);
		assertTrue(reg.allowRequest("openai", "i1"));
		reg.onFailure("openai", "i1");
		// 断言点：探测失败回 OPEN 并立即拒绝
		assertEquals(CircuitBreaker.State.OPEN, reg.snapshot("openai", "i1").state());
		assertFalse(reg.allowRequest("openai", "i1"));
	}

	/** 未触发的 client snapshot 返回 null。 */
	@Test
	public void snapshotNullWhenNeverUsed() {
		CircuitBreakerRegistry reg = CircuitBreakerRegistry.defaults();
		// 断言点：从未创建的 client 无快照
		assertNull(reg.snapshot("nope", "nope"));
		assertEquals(0, reg.size());
	}

	/** snapshots 返回 platform|instanceId 维度的不可变副本。 */
	@Test
	public void snapshotsMapAndImmutable() {
		CircuitBreakerRegistry reg = CircuitBreakerRegistry.defaults();
		reg.onSuccess("openai", "i1");
		reg.onFailure("deepseek", "i2");
		Map<String, Snapshot> snap = reg.snapshots();
		// 断言点：两个维度各有一条快照
		assertEquals(2, snap.size());
		assertNotNull(snap.get("openai|i1"));
		assertNotNull(snap.get("deepseek|i2"));
		try {
			snap.put("x|y", null);
			fail("应当不可变");
		} catch (UnsupportedOperationException expected) {
			// 断言点：返回不可变副本
		}
	}

	/** reset 清空全部熔断器实例。 */
	@Test
	public void resetClears() {
		CircuitBreakerRegistry reg = CircuitBreakerRegistry.defaults();
		reg.onSuccess("openai", "i1");
		assertEquals(1, reg.size());
		reg.reset();
		// 断言点：清空后 size 归零且 snapshot 重新为 null
		assertEquals(0, reg.size());
		assertNull(reg.snapshot("openai", "i1"));
	}

	/** 不同 (platform, instanceId) 维度互不影响。 */
	@Test
	public void dimensionsAreIsolated() {
		CircuitBreakerRegistry reg = new CircuitBreakerRegistry(
				CircuitBreakerConfig.builder().failureThreshold(2).build());
		reg.onFailure("openai", "i1");
		reg.onFailure("openai", "i1");
		// 断言点：i1 已 OPEN，i2 仍 CLOSED 放行
		assertFalse(reg.allowRequest("openai", "i1"));
		assertTrue(reg.allowRequest("openai", "i2"));
		assertTrue(reg.allowRequest("other", "i1"));
		assertEquals(3, reg.size());
	}

	/** null 配置回退默认值。 */
	@Test
	public void nullConfigFallsBackToDefaults() {
		CircuitBreakerRegistry reg = new CircuitBreakerRegistry(null);
		reg.onSuccess("openai", "i1");
		// 断言点：null 配置可用且正常工作
		assertNotNull(reg.snapshot("openai", "i1"));
	}
}
