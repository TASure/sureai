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

import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * {@link LatencyTracker} 单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class LatencyTrackerTest {

	/** 记录多条延迟后平均正确。 */
	@Test
	public void recordAndAverage() {
		long[] now = { 0L };
		LatencyTracker tracker = new LatencyTracker(() -> now[0]);
		tracker.record("p", "i", 100L);
		tracker.record("p", "i", 200L);

		assertEquals(150.0, tracker.averageLatency("p", "i"), 0.001);
	}

	/** 滑动窗口：超过窗口长度的旧样本过期后不影响平均。 */
	@Test
	public void slidingWindowEvictsOld() {
		long[] now = { 0L };
		LatencyTracker tracker = new LatencyTracker(() -> now[0]);
		tracker.record("p", "i", 100L);
		assertEquals(100.0, tracker.averageLatency("p", "i"), 0.001);

		// 时间快进 6 分钟，超过 5 分钟窗口
		now[0] = TimeUnit.MINUTES.toMillis(6);
		tracker.record("p", "i", 200L);

		assertEquals("old sample should have expired", 200.0,
				tracker.averageLatency("p", "i"), 0.001);
	}
}
