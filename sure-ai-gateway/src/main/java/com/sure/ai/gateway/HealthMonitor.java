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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sure.ai.client.resilience.CircuitBreaker;

/**
 * 健康监视器：定期对注册表候选做探活，并汇总健康视图。
 *
 * <p>每轮 {@link #runOnce()} 遍历 {@link ClientRegistry#candidates()}：探活成功则
 * {@link ClientRegistry#markHealthy}，失败或探活抛异常则 {@link ClientRegistry#markUnhealthy}
 * （摘除一个冷却周期）。探活结果与时间戳被记录，供 {@link #healthView()} 查询。</p>
 *
 * <p>调度为单线程 daemon：{@link #start()} 幂等（重复调用不产生第二个调度），
 * {@link #stop()} 幂等（{@code shutdownNow}）。调度器可在包内构造器注入以便测试。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public final class HealthMonitor {

	/** 默认不健康冷却毫秒（30s）。 */
	static final long DEFAULT_UNHEALTHY_COOLDOWN_MS = 30_000L;

	/** 默认探活间隔毫秒（15s）。 */
	static final long DEFAULT_INTERVAL_MS = 15_000L;

	/** 注册表。 */
	private final ClientRegistry registry;

	/** 探活 SPI。 */
	private final HealthProbe probe;

	/** 不健康冷却毫秒。 */
	private final long unhealthyCooldownMs;

	/** 探活间隔毫秒。 */
	private final long intervalMs;

	/** 调度器（可注入）。 */
	private final ScheduledExecutorService scheduler;

	/** 是否已 start（幂等守卫）。 */
	private final AtomicBoolean started = new AtomicBoolean(false);

	/** "platform|instanceId" → 最近一次探活是否健康。 */
	private final Map<String, Boolean> probeResults = new ConcurrentHashMap<>();

	/** "platform|instanceId" → 最近一次探活时间戳。 */
	private final Map<String, Long> lastProbeAt = new ConcurrentHashMap<>();

	/** 可选：熔断器注册表，用于在健康视图中展示熔断状态。 */
	private CircuitBreakerRegistry breakers;

	/**
	 * 全参构造器（默认冷却 30s、间隔 15s，自建 daemon 调度器）。
	 *
	 * @param registry 注册表
	 * @param probe    探活 SPI
	 */
	public HealthMonitor(ClientRegistry registry, HealthProbe probe) {
		this(registry, probe, DEFAULT_UNHEALTHY_COOLDOWN_MS, DEFAULT_INTERVAL_MS);
	}

	/**
	 * 全参构造器（自建 daemon 调度器）。
	 *
	 * @param registry             注册表
	 * @param probe                探活 SPI
	 * @param unhealthyCooldownMs  不健康冷却毫秒
	 * @param intervalMs           探活间隔毫秒
	 */
	public HealthMonitor(ClientRegistry registry, HealthProbe probe, long unhealthyCooldownMs,
			long intervalMs) {
		this(registry, probe, unhealthyCooldownMs, intervalMs,
				Executors.newSingleThreadScheduledExecutor(r -> {
					Thread t = new Thread(r, "sure-ai-health-monitor");
					t.setDaemon(true);
					return t;
				}));
	}

	/**
	 * 测试用构造器：注入调度器（不依赖真实调度时序，测试只调 runOnce / healthView）。
	 *
	 * @param registry             注册表
	 * @param probe                探活 SPI
	 * @param unhealthyCooldownMs  不健康冷却毫秒
	 * @param intervalMs           探活间隔毫秒
	 * @param scheduler            注入的调度器
	 */
	HealthMonitor(ClientRegistry registry, HealthProbe probe, long unhealthyCooldownMs,
			long intervalMs, ScheduledExecutorService scheduler) {
		this.registry = registry;
		this.probe = probe;
		this.unhealthyCooldownMs = unhealthyCooldownMs;
		this.intervalMs = intervalMs;
		this.scheduler = scheduler;
	}

	/**
	 * 挂载熔断器注册表（用于健康视图展示熔断状态）。
	 *
	 * @param breakers 熔断器注册表
	 */
	void breakers(CircuitBreakerRegistry breakers) {
		this.breakers = breakers;
	}

	/** 启动定期探活调度（幂等）。 */
	public void start() {
		if (this.started.compareAndSet(false, true)) {
			this.scheduler.scheduleAtFixedRate(this::runOnce,
					this.intervalMs, this.intervalMs, TimeUnit.MILLISECONDS);
		}
	}

	/** 停止调度（幂等，shutdownNow）。 */
	public void stop() {
		this.scheduler.shutdownNow();
	}

	/** 同步执行一轮探活：遍历候选，更新健康状态与探活记录。 */
	public void runOnce() {
		long now = System.currentTimeMillis();
		for (ClientCandidate c : this.registry.candidates()) {
			boolean healthy;
			try {
				healthy = this.probe.isHealthy(c.client(), c.platform(), c.instanceId());
			} catch (RuntimeException e) {
				healthy = false;
			}
			String key = c.platform() + '|' + c.instanceId();
			this.probeResults.put(key, healthy);
			this.lastProbeAt.put(key, now);
			if (healthy) {
				this.registry.markHealthy(c.platform(), c.instanceId());
			} else {
				this.registry.markUnhealthy(c.platform(), c.instanceId(), this.unhealthyCooldownMs);
			}
		}
	}

	/**
	 * 汇总所有候选的健康视图。
	 *
	 * @return 健康视图列表
	 */
	public List<ClientHealthView> healthView() {
		List<ClientHealthView> out = new ArrayList<>();
		for (ClientCandidate c : this.registry.candidates()) {
			String key = c.platform() + '|' + c.instanceId();
			Boolean probeOk = this.probeResults.get(key);
			long last = this.lastProbeAt.getOrDefault(key, 0L);
			boolean healthy = this.registry.isHealthy(c.platform(), c.instanceId());
			CircuitBreaker.State state = null;
			if (this.breakers != null) {
				CircuitBreaker.Snapshot snap = this.breakers.snapshot(c.platform(), c.instanceId());
				if (snap != null) {
					state = snap.state();
				}
			}
			out.add(new ClientHealthView(c.platform(), c.instanceId(), healthy, state, probeOk, last));
		}
		return out;
	}
}
