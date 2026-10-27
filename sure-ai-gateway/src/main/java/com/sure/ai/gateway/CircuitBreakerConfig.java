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

/**
 * 网关级熔断器配置：按 {@code (平台, 实例)} 维度套用的三态熔断参数。
 *
 * <p>不可变 record，紧凑构造器做参数校验；提供 {@link #defaults()} 与 {@link #builder()}。
 * 与 core 的 {@code CircuitBreaker.Builder} 参数一一对应。</p>
 *
 * @param failureThreshold       滑动窗口内失败多少次即转 OPEN（≥1）
 * @param windowSize             滑动窗口大小（最近 N 次请求，≥ failureThreshold）
 * @param openTimeoutMs          OPEN 状态停留时长（毫秒，&gt;0）
 * @param halfOpenPermittedCalls HALF_OPEN 允许的探测请求数（≥1）
 * @author sureai
 * @since 2.7.0
 */
public record CircuitBreakerConfig(int failureThreshold, int windowSize, long openTimeoutMs,
		int halfOpenPermittedCalls) {

	/**
	 * 紧凑构造器：校验全部参数，违规抛 {@link IllegalArgumentException}。
	 */
	public CircuitBreakerConfig {
		if (failureThreshold < 1) {
			throw new IllegalArgumentException("failureThreshold must be >= 1");
		}
		if (windowSize < failureThreshold) {
			throw new IllegalArgumentException("windowSize must be >= failureThreshold");
		}
		if (openTimeoutMs <= 0) {
			throw new IllegalArgumentException("openTimeoutMs must be > 0");
		}
		if (halfOpenPermittedCalls < 1) {
			throw new IllegalArgumentException("halfOpenPermittedCalls must be >= 1");
		}
	}

	/**
	 * 默认配置：失败阈值 3 / 窗口 5 / 打开超时 10000ms / 半开放探测 1。
	 *
	 * @return 默认配置
	 */
	public static CircuitBreakerConfig defaults() {
		return new CircuitBreakerConfig(3, 5, 10_000L, 1);
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * {@link CircuitBreakerConfig} 可变 Builder。
	 */
	public static final class Builder {

		private int failureThreshold = 3;
		private int windowSize = 5;
		private long openTimeoutMs = 10_000L;
		private int halfOpenPermittedCalls = 1;

		private Builder() {
		}

		/**
		 * 滑动窗口内失败多少次即转 OPEN。
		 *
		 * @param failureThreshold 失败阈值
		 * @return this
		 */
		public Builder failureThreshold(int failureThreshold) {
			this.failureThreshold = failureThreshold;
			return this;
		}

		/**
		 * 滑动窗口大小。
		 *
		 * @param windowSize 窗口大小
		 * @return this
		 */
		public Builder windowSize(int windowSize) {
			this.windowSize = windowSize;
			return this;
		}

		/**
		 * OPEN 状态停留时长（毫秒）。
		 *
		 * @param openTimeoutMs 打开超时毫秒
		 * @return this
		 */
		public Builder openTimeoutMs(long openTimeoutMs) {
			this.openTimeoutMs = openTimeoutMs;
			return this;
		}

		/**
		 * HALF_OPEN 允许的探测请求数。
		 *
		 * @param halfOpenPermittedCalls 探测请求数
		 * @return this
		 */
		public Builder halfOpenPermittedCalls(int halfOpenPermittedCalls) {
			this.halfOpenPermittedCalls = halfOpenPermittedCalls;
			return this;
		}

		/**
		 * 构建不可变配置（交由 record 紧凑构造器校验）。
		 *
		 * @return 熔断配置
		 */
		public CircuitBreakerConfig build() {
			return new CircuitBreakerConfig(this.failureThreshold, this.windowSize,
					this.openTimeoutMs, this.halfOpenPermittedCalls);
		}
	}
}
