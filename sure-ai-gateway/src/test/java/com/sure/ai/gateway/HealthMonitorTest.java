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

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.resilience.CircuitBreaker;

/**
 * {@link HealthMonitor} 单元测试：纯内存 + Fake 客户端，零网络、不依赖真实调度时序。
 *
 * @author sureai
 * @since 2.7.0
 */
public class HealthMonitorTest {

	/** 记录 scheduleAtFixedRate 调用次数的调度器（不真正跑周期任务）。 */
	private static final class CountingScheduler extends ScheduledThreadPoolExecutor {

		/** scheduleAtFixedRate 被调用次数。 */
		int scheduled;

		CountingScheduler() {
			super(1);
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable cmd,
				long initialDelay, long delay, TimeUnit unit) {
			this.scheduled++;
			return super.scheduleAtFixedRate(cmd, 3600_000L, 3600_000L, unit);
		}
	}

	/** 构造一个含两个实例的注册表。 */
	private static ClientRegistry twoInstanceRegistry() {
		ClientRegistry registry = new ClientRegistry();
		registry.register("openai", "us", new FakeClient("us"));
		registry.register("openai", "eu", new FakeClient("eu"));
		return registry;
	}

	/** 探活返回 false → markUnhealthy 且 healthView 显示不健康。 */
	@Test
	public void probeFalseMarksUnhealthy() {
		ClientRegistry registry = twoInstanceRegistry();
		HealthMonitor monitor = new HealthMonitor(registry, (c, p, i) -> false, 60_000L, 15_000L);
		monitor.runOnce();
		// 断言点：两个实例均被标记不健康
		assertFalse(registry.isHealthy("openai", "us"));
		assertFalse(registry.isHealthy("openai", "eu"));
		List<ClientHealthView> views = monitor.healthView();
		assertEquals(2, views.size());
		for (ClientHealthView v : views) {
			assertFalse(v.healthy());
			assertEquals(Boolean.FALSE, v.probeHealthy());
		}
	}

	/** 探活返回 true → 恢复健康。 */
	@Test
	public void probeTrueMarksHealthy() {
		ClientRegistry registry = twoInstanceRegistry();
		registry.markUnhealthy("openai", "us", 60_000L);
		HealthMonitor monitor = new HealthMonitor(registry, (c, p, i) -> true, 60_000L, 15_000L);
		monitor.runOnce();
		// 断言点：探活成功后恢复健康
		assertTrue(registry.isHealthy("openai", "us"));
		assertTrue(registry.isHealthy("openai", "eu"));
		assertEquals(Boolean.TRUE, monitor.healthView().get(0).probeHealthy());
	}

	/** 自定义 probe 注入生效（按实例区分返回值）。 */
	@Test
	public void customProbeInjected() {
		ClientRegistry registry = twoInstanceRegistry();
		HealthProbe probe = (AiClient c, String p, String i) -> "us".equals(i);
		HealthMonitor monitor = new HealthMonitor(registry, probe, 60_000L, 15_000L);
		monitor.runOnce();
		// 断言点：us 健康、eu 不健康，按自定义 probe 结果区分
		assertTrue(registry.isHealthy("openai", "us"));
		assertFalse(registry.isHealthy("openai", "eu"));
	}

	/** probe 抛运行时异常 → 该候选按不健康处理。 */
	@Test
	public void probeExceptionTreatedAsUnhealthy() {
		ClientRegistry registry = twoInstanceRegistry();
		HealthProbe probe = (c, p, i) -> {
			throw new IllegalStateException("boom");
		};
		HealthMonitor monitor = new HealthMonitor(registry, probe, 60_000L, 15_000L);
		monitor.runOnce();
		// 断言点：异常被捕获并按不健康摘除
		assertFalse(registry.isHealthy("openai", "us"));
		assertFalse(registry.isHealthy("openai", "eu"));
	}

	/** healthView 含熔断状态与最近探活时间。 */
	@Test
	public void healthViewIncludesCircuitStateAndTimestamp() {
		ClientRegistry registry = twoInstanceRegistry();
		CircuitBreakerRegistry breakers = new CircuitBreakerRegistry(
				CircuitBreakerConfig.builder().failureThreshold(2).build());
		breakers.onFailure("openai", "us");
		breakers.onFailure("openai", "us");
		HealthMonitor monitor = new HealthMonitor(registry, new DefaultHealthProbe(),
				60_000L, 15_000L);
		monitor.breakers(breakers);
		long before = System.currentTimeMillis();
		monitor.runOnce();
		List<ClientHealthView> views = monitor.healthView();
		ClientHealthView us = views.stream()
			.filter(v -> "us".equals(v.instanceId()))
			.findFirst()
			.orElseThrow();
		// 断言点：us 熔断器为 OPEN，eu 未配置熔断为 null
		assertEquals(CircuitBreaker.State.OPEN, us.circuitState());
		ClientHealthView eu = views.stream()
			.filter(v -> "eu".equals(v.instanceId()))
			.findFirst()
			.orElseThrow();
		assertNull(eu.circuitState());
		// 断言点：最近探活时间戳落在 [before, now] 区间
		assertTrue(us.lastProbeAtMs() >= before);
		assertTrue(us.lastProbeAtMs() <= System.currentTimeMillis());
	}

	/** 从未探活的候选 probeHealthy 为 null、lastProbeAtMs 为 0。 */
	@Test
	public void neverProbedViewHasNullProbeResult() {
		ClientRegistry registry = twoInstanceRegistry();
		HealthMonitor monitor = new HealthMonitor(registry, new DefaultHealthProbe());
		List<ClientHealthView> views = monitor.healthView();
		// 断言点：未 runOnce 时视图仍列出全部候选但探活字段为空
		assertEquals(2, views.size());
		assertNull(views.get(0).probeHealthy());
		assertEquals(0L, views.get(0).lastProbeAtMs());
	}

	/** start 幂等：重复 start 只调度一次；stop 幂等。 */
	@Test
	public void startIdempotentAndStopIdempotent() {
		ClientRegistry registry = twoInstanceRegistry();
		CountingScheduler scheduler = new CountingScheduler();
		HealthMonitor monitor = new HealthMonitor(registry, new DefaultHealthProbe(),
				1_000L, 1_000L, scheduler);
		monitor.start();
		monitor.start();
		// 断言点：两次 start 只产生一次调度
		assertEquals(1, scheduler.scheduled);
		monitor.stop();
		monitor.stop();
		// 断言点：两次 stop 不抛异常
		assertTrue(true);
	}

	/** 默认构造器可 start/stop（不抛异常）。 */
	@Test
	public void defaultConstructorsWork() {
		ClientRegistry registry = twoInstanceRegistry();
		HealthMonitor monitor = new HealthMonitor(registry, new DefaultHealthProbe());
		monitor.start();
		monitor.stop();
		// 断言点：默认冷却/间隔构造器与 start/stop 正常
		assertNotNull(monitor);
	}
}
