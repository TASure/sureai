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

package com.sure.ai.micrometer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * {@link MicrometerMetricsAdapter} 单测：用 SimpleMeterRegistry 验证埋点。
 *
 * @author sureai
 * @since 0.3.0
 */
public class MicrometerMetricsAdapterTest {

	private SimpleMeterRegistry registry() {
		return new SimpleMeterRegistry();
	}

	private double count(SimpleMeterRegistry r, String name, String tagKey, String tagValue) {
		return r.find(name).tag(tagKey, tagValue).counter().count();
	}

	/** 成功：success counter + duration timer 记录。 */
	@Test
	public void testOnRequestSuccess() {
		SimpleMeterRegistry r = registry();
		MicrometerMetricsAdapter a = new MicrometerMetricsAdapter(r);
		a.onRequestStart("/chat");
		a.onRequestSuccess("/chat", 200, 120);
		assertEquals(1.0, count(r, "sureai.requests.success", "path", "/chat"), 1e-9);
		assertEquals(1.0, count(r, "sureai.requests.success", "status", "200"), 1e-9);
		Timer timer = r.find("sureai.request.duration").tag("path", "/chat").timer();
		assertEquals(1L, timer.count());
		assertEquals(120.0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 1e-6);
	}

	/** 失败：failure counter 记录。 */
	@Test
	public void testOnRequestFailure() {
		SimpleMeterRegistry r = registry();
		MicrometerMetricsAdapter a = new MicrometerMetricsAdapter(r);
		a.onRequestFailure("/chat", 500, new RuntimeException("boom"), 50);
		assertEquals(1.0, count(r, "sureai.requests.failure", "path", "/chat"), 1e-9);
		assertEquals(1.0, count(r, "sureai.requests.failure", "status", "500"), 1e-9);
	}

	/** 重试：retry counter 记录。 */
	@Test
	public void testOnRetry() {
		SimpleMeterRegistry r = registry();
		MicrometerMetricsAdapter a = new MicrometerMetricsAdapter(r);
		a.onRetry("/chat", 1, 429);
		a.onRetry("/chat", 2, 503);
		assertEquals(1.0, count(r, "sureai.requests.retry", "status", "429"), 1e-9);
		assertEquals(1.0, count(r, "sureai.requests.retry", "status", "503"), 1e-9);
	}

	/** Token：三类 token counter 累计。 */
	@Test
	public void testOnTokenUsage() {
		SimpleMeterRegistry r = registry();
		MicrometerMetricsAdapter a = new MicrometerMetricsAdapter(r);
		a.onTokenUsage("gpt", 10, 5, 15);
		a.onTokenUsage("gpt", 20, 7, 27);
		assertEquals(30.0, count(r, "sureai.tokens.prompt", "model", "gpt"), 1e-9);
		assertEquals(12.0, count(r, "sureai.tokens.completion", "model", "gpt"), 1e-9);
		assertEquals(42.0, count(r, "sureai.tokens.total", "model", "gpt"), 1e-9);
	}

	/** null path/model 退化为 unknown，不抛异常。 */
	@Test
	public void testNullSafe() {
		SimpleMeterRegistry r = registry();
		MicrometerMetricsAdapter a = new MicrometerMetricsAdapter(r);
		a.onRequestSuccess(null, 200, 1);
		a.onTokenUsage(null, 1, 1, 2);
		assertEquals(1.0, count(r, "sureai.requests.success", "path", "unknown"), 1e-9);
	}
}
