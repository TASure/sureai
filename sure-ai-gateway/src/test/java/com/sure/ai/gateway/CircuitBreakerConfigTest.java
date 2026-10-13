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
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link CircuitBreakerConfig} 单元测试。
 *
 * @author sureai
 * @since 2.7.0
 */
public class CircuitBreakerConfigTest {

	/** 默认值为 3/5/10000/1。 */
	@Test
	public void defaultsAreAsExpected() {
		CircuitBreakerConfig c = CircuitBreakerConfig.defaults();
		assertEquals(3, c.failureThreshold());
		assertEquals(5, c.windowSize());
		assertEquals(10_000L, c.openTimeoutMs());
		assertEquals(1, c.halfOpenPermittedCalls());
	}

	/** builder 各参数透传到 record。 */
	@Test
	public void builderSetsAllParams() {
		CircuitBreakerConfig c = CircuitBreakerConfig.builder()
			.failureThreshold(2)
			.windowSize(4)
			.openTimeoutMs(500L)
			.halfOpenPermittedCalls(2)
			.build();
		assertEquals(2, c.failureThreshold());
		assertEquals(4, c.windowSize());
		assertEquals(500L, c.openTimeoutMs());
		assertEquals(2, c.halfOpenPermittedCalls());
	}

	/** failureThreshold < 1 抛 IllegalArgumentException。 */
	@Test
	public void rejectsFailureThresholdBelowOne() {
		try {
			new CircuitBreakerConfig(0, 5, 1000L, 1);
			fail("应当抛异常");
		} catch (IllegalArgumentException expected) {
			// 断言点：失败阈值非法
		}
	}

	/** windowSize < failureThreshold 抛 IllegalArgumentException。 */
	@Test
	public void rejectsWindowSmallerThanThreshold() {
		try {
			new CircuitBreakerConfig(5, 3, 1000L, 1);
			fail("应当抛异常");
		} catch (IllegalArgumentException expected) {
			// 断言点：窗口小于失败阈值
		}
	}

	/** openTimeoutMs <= 0 抛 IllegalArgumentException。 */
	@Test
	public void rejectsNonPositiveTimeout() {
		try {
			new CircuitBreakerConfig(3, 5, 0L, 1);
			fail("应当抛异常");
		} catch (IllegalArgumentException expected) {
			// 断言点：打开超时非正
		}
	}

	/** halfOpenPermittedCalls < 1 抛 IllegalArgumentException。 */
	@Test
	public void rejectsHalfOpenBelowOne() {
		try {
			CircuitBreakerConfig.builder().halfOpenPermittedCalls(0).build();
			fail("应当抛异常");
		} catch (IllegalArgumentException expected) {
			// 断言点：半开放探测数非法
		}
	}
}
