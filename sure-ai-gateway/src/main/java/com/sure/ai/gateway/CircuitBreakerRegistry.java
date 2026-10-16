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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sure.ai.client.resilience.CircuitBreaker;

/**
 * 网关级熔断器注册表：按 {@code (平台, 实例ID)} 维度持有 core {@link CircuitBreaker}。
 *
 * <p>直接复用 core 的三态熔断器（closed / open / half-open、失败阈值 / 冷却窗口 / 最小探测数），
 * 本类只做维度持有与查询：懒创建每个 client 对应的熔断器实例，线程安全（{@link ConcurrentHashMap}）。
 * OPEN 时 {@link #allowRequest} 快速失败，由 {@code GatewayClient} 在路由时过滤（本批不碰 GatewayClient）。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public final class CircuitBreakerRegistry {

	/** "platform|instanceId" → 熔断器实例。 */
	private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

	/** 熔断配置。 */
	private final CircuitBreakerConfig config;

	/**
	 * 构造器；config 为 null 时使用 {@link CircuitBreakerConfig#defaults()}。
	 *
	 * @param config 熔断配置
	 */
	public CircuitBreakerRegistry(CircuitBreakerConfig config) {
		this.config = config == null ? CircuitBreakerConfig.defaults() : config;
	}

	/**
	 * 默认配置注册表。
	 *
	 * @return 默认注册表
	 */
	public static CircuitBreakerRegistry defaults() {
		return new CircuitBreakerRegistry(CircuitBreakerConfig.defaults());
	}

	/**
	 * 是否允许本次请求通过；无熔断器实例时按需创建并转发 core {@code allowRequest}。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 * @return true=放行
	 */
	public boolean allowRequest(String platform, String instanceId) {
		return breaker(platform, instanceId).allowRequest();
	}

	/**
	 * 请求成功回调；无实例时按需创建。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 */
	public void onSuccess(String platform, String instanceId) {
		breaker(platform, instanceId).onSuccess();
	}

	/**
	 * 请求失败回调；无实例时按需创建。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 */
	public void onFailure(String platform, String instanceId) {
		breaker(platform, instanceId).onFailure();
	}

	/**
	 * 某 client 的熔断器快照；未创建过返回 null。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 * @return 快照，未创建返回 null
	 */
	public CircuitBreaker.Snapshot snapshot(String platform, String instanceId) {
		CircuitBreaker cb = this.breakers.get(key(platform, instanceId));
		return cb == null ? null : cb.snapshot();
	}

	/**
	 * 全部已创建熔断器的快照（key 为 {@code platform|instanceId}），返回不可变副本。
	 *
	 * @return 不可变快照映射
	 */
	public Map<String, CircuitBreaker.Snapshot> snapshots() {
		Map<String, CircuitBreaker.Snapshot> out = new ConcurrentHashMap<>();
		this.breakers.forEach((k, v) -> out.put(k, v.snapshot()));
		return Map.copyOf(out);
	}

	/**
	 * 已创建熔断器实例数。
	 *
	 * @return 数量
	 */
	public int size() {
		return this.breakers.size();
	}

	/** 清空全部熔断器实例。 */
	public void reset() {
		this.breakers.clear();
	}

	/** 取或创建某 client 的熔断器。 */
	private CircuitBreaker breaker(String platform, String instanceId) {
		return this.breakers.computeIfAbsent(key(platform, instanceId),
				k -> CircuitBreaker.builder()
					.failureThreshold(this.config.failureThreshold())
					.windowSize(this.config.windowSize())
					.openTimeoutMs(this.config.openTimeoutMs())
					.halfOpenPermittedCalls(this.config.halfOpenPermittedCalls())
					.build());
	}

	private static String key(String platform, String instanceId) {
		return platform + '|' + instanceId;
	}
}
