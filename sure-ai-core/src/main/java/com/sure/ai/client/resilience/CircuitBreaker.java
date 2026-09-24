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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

import com.sure.tool.lang.Assert;

/**
 * 熔断器：跨请求的故障状态机，包裹在单次请求重试逻辑之外。
 *
 * <p>三态：</p>
 * <ul>
 *   <li>{@link State#CLOSED}：正常放行，把每次结果（成功/失败）记入滑动窗口；
 *       窗口内失败数达到 {@code failureThreshold} 即转为 {@link State#OPEN}。</li>
 *   <li>{@link State#OPEN}：快速失败，{@link #allowRequest()} 一律拒绝（不发网络、不触发重试/指标/限流）；
 *       停留 {@code openTimeoutMs} 后惰性转为 {@link State#HALF_OPEN}。</li>
 *   <li>{@link State#HALF_OPEN}：放行 {@code halfOpenPermittedCalls} 个探测请求；
 *       全部探测成功则回到 {@link State#CLOSED} 并重置窗口，任一探测失败立即回
 *       {@link State#OPEN} 并重记打开时间。</li>
 * </ul>
 *
 * <p>与重试的关系：重试是单次请求内部的退避（429/5xx 之间休眠重发），熔断器是跨请求的
 * 状态机——在重试循环之外再包一层。OPEN 时直接快速失败，根本不进入重试循环。</p>
 *
 * <p>线程安全：所有共享状态由 {@link ReentrantLock} 保护；{@link #snapshot()} 返回不可变快照。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class CircuitBreaker {

	/** 熔断器状态。 */
	public enum State {
		/** 闭合：正常放行。 */
		CLOSED,
		/** 打开：快速失败。 */
		OPEN,
		/** 半开：放行少量探测请求。 */
		HALF_OPEN
	}

	private final int failureThreshold;
	private final int windowSize;
	private final long openTimeoutMs;
	private final int halfOpenPermittedCalls;

	private final ReentrantLock lock = new ReentrantLock();
	private State state = State.CLOSED;
	/** CLOSED 下的滑动窗口（最近 N 次结果，true=成功，false=失败）。 */
	private final Deque<Boolean> window = new ArrayDeque<>();
	private long totalCalls;
	private long openedAt;
	/** HALF_OPEN 已放行的探测请求数。 */
	private int halfOpenProbeCount;
	/** HALF_OPEN 探测成功数。 */
	private int halfOpenSuccessCount;

	private CircuitBreaker(Builder b) {
		this.failureThreshold = b.failureThreshold;
		this.windowSize = b.windowSize;
		this.openTimeoutMs = b.openTimeoutMs;
		this.halfOpenPermittedCalls = b.halfOpenPermittedCalls;
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
	 * 是否允许本次请求通过。
	 *
	 * <p>CLOSED 总是放行；OPEN 未超时一律拒绝，超时后惰性转 HALF_OPEN 并放行首个探测；
	 * HALF_OPEN 放行直至配额用完。</p>
	 *
	 * @return true=放行（调用方随后执行请求，并在结束时回调 {@link #onSuccess()} / {@link #onFailure()}）
	 */
	public boolean allowRequest() {
		this.lock.lock();
		try {
			if (this.state == State.OPEN) {
				if (System.currentTimeMillis() - this.openedAt >= this.openTimeoutMs) {
					this.state = State.HALF_OPEN;
					this.halfOpenProbeCount = 0;
					this.halfOpenSuccessCount = 0;
				} else {
					return false;
				}
			}
			if (this.state == State.HALF_OPEN) {
				if (this.halfOpenProbeCount < this.halfOpenPermittedCalls) {
					this.halfOpenProbeCount++;
					return true;
				}
				return false;
			}
			return true;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * 请求成功回调。
	 *
	 * <p>CLOSED：把成功记入滑动窗口；HALF_OPEN：探测成功，全部探测成功则回到 CLOSED 并重置。</p>
	 */
	public void onSuccess() {
		this.lock.lock();
		try {
			this.totalCalls++;
			if (this.state == State.HALF_OPEN) {
				this.halfOpenSuccessCount++;
				if (this.halfOpenSuccessCount >= this.halfOpenPermittedCalls) {
					this.state = State.CLOSED;
					resetWindow();
					this.openedAt = 0L;
					this.halfOpenProbeCount = 0;
					this.halfOpenSuccessCount = 0;
				}
				return;
			}
			if (this.state == State.CLOSED) {
				recordOutcome(true);
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * 请求失败回调。
	 *
	 * <p>CLOSED：把失败记入滑动窗口，达阈值则转 OPEN；HALF_OPEN：探测失败立即回 OPEN。</p>
	 */
	public void onFailure() {
		this.lock.lock();
		try {
			this.totalCalls++;
			if (this.state == State.HALF_OPEN) {
				this.state = State.OPEN;
				this.openedAt = System.currentTimeMillis();
				resetWindow();
				this.halfOpenProbeCount = 0;
				this.halfOpenSuccessCount = 0;
				return;
			}
			if (this.state == State.CLOSED) {
				recordOutcome(false);
				if (countFailures() >= this.failureThreshold) {
					this.state = State.OPEN;
					this.openedAt = System.currentTimeMillis();
					resetWindow();
				}
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * 当前状态（惰性检查 OPEN 是否超时）。
	 *
	 * @return 当前状态
	 */
	public State state() {
		this.lock.lock();
		try {
			if (this.state == State.OPEN && System.currentTimeMillis() - this.openedAt >= this.openTimeoutMs) {
				return State.HALF_OPEN;
			}
			return this.state;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * 不可变快照。
	 *
	 * @return 快照
	 */
	public Snapshot snapshot() {
		this.lock.lock();
		try {
			State s = this.state;
			if (s == State.OPEN && System.currentTimeMillis() - this.openedAt >= this.openTimeoutMs) {
				s = State.HALF_OPEN;
			}
			int failures = countFailures();
			int successes = this.window.size() - failures;
			return new Snapshot(s, failures, successes, this.totalCalls, this.openedAt);
		} finally {
			this.lock.unlock();
		}
	}

	/** 记录一次结果到滑动窗口，超出窗口大小时淘汰最旧。 */
	private void recordOutcome(boolean success) {
		this.window.addLast(success);
		while (this.window.size() > this.windowSize) {
			this.window.removeFirst();
		}
	}

	/** 统计窗口内失败数。 */
	private int countFailures() {
		int f = 0;
		for (Boolean b : this.window) {
			if (!b) {
				f++;
			}
		}
		return f;
	}

	/** 清空滑动窗口。 */
	private void resetWindow() {
		this.window.clear();
	}

	/**
	 * 熔断器不可变快照。
	 *
	 * @param state       状态（惰性解析后的有效状态）
	 * @param failureCount 当前窗口内失败数（非 CLOSED 时为 0）
	 * @param successCount 当前窗口内成功数（非 CLOSED 时为 0）
	 * @param totalCalls  自创建以来已完成请求总数
	 * @param openedAt    最近一次进入 OPEN 的时间戳（毫秒），未打开为 0
	 */
	public record Snapshot(State state, int failureCount, int successCount, long totalCalls, long openedAt) {
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private int failureThreshold = 3;
		private int windowSize = 5;
		private long openTimeoutMs = 10_000L;
		private int halfOpenPermittedCalls = 1;

		private Builder() {
		}

		/**
		 * 滑动窗口内失败多少次即转 OPEN（默认 3）。
		 *
		 * @param failureThreshold 失败阈值
		 * @return this
		 */
		public Builder failureThreshold(int failureThreshold) {
			this.failureThreshold = failureThreshold;
			return this;
		}

		/**
		 * 滑动窗口大小（最近 N 次请求，默认 5）。
		 *
		 * @param windowSize 窗口大小
		 * @return this
		 */
		public Builder windowSize(int windowSize) {
			this.windowSize = windowSize;
			return this;
		}

		/**
		 * OPEN 状态停留时长（毫秒，默认 10000）。
		 *
		 * @param openTimeoutMs 打开超时毫秒
		 * @return this
		 */
		public Builder openTimeoutMs(long openTimeoutMs) {
			this.openTimeoutMs = openTimeoutMs;
			return this;
		}

		/**
		 * HALF_OPEN 允许的探测请求数（默认 1）。
		 *
		 * @param halfOpenPermittedCalls 探测请求数
		 * @return this
		 */
		public Builder halfOpenPermittedCalls(int halfOpenPermittedCalls) {
			this.halfOpenPermittedCalls = halfOpenPermittedCalls;
			return this;
		}

		/**
		 * 构建。
		 *
		 * @return 熔断器
		 */
		public CircuitBreaker build() {
			Assert.isTrue(this.failureThreshold >= 1, "failureThreshold must be >= 1");
			Assert.isTrue(this.windowSize >= this.failureThreshold, "windowSize must be >= failureThreshold");
			Assert.isTrue(this.openTimeoutMs > 0, "openTimeoutMs must be > 0");
			Assert.isTrue(this.halfOpenPermittedCalls >= 1, "halfOpenPermittedCalls must be >= 1");
			return new CircuitBreaker(this);
		}
	}
}
