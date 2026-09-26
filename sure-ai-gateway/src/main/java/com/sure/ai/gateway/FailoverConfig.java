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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;

/**
 * 故障转移配置：控制最大尝试次数、可转移异常类型、不健康冷却时间与事件监听。
 *
 * <h2>异常分类（默认策略）</h2>
 * <ul>
 *   <li><b>可转移</b>：{@link AiTimeoutException}；{@link AiApiException} 且 HTTP 状态码
 *       ≥ 500（服务端错误）；以及任何非 {@link AiException} 的原始异常（如连接层 IOException）。</li>
 *   <li><b>不可转移</b>：{@link AiApiException} 且状态码为 4xx（含 401/403 鉴权、429 限流）——
 *       这类错误换一个平台通常也会复现，直接抛给调用方。调用方可把限流异常等显式加入
 *       {@link Builder#retryable} 以开启跨平台转移。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class FailoverConfig {

	/** 默认最大尝试次数。 */
	public static final int DEFAULT_MAX_ATTEMPTS = 3;

	/** 默认不健康冷却时间（秒）。 */
	public static final long DEFAULT_COOLDOWN_SECONDS = 30L;

	/** 最大尝试次数。 */
	private final int maxAttempts;

	/** 额外可转移异常类型。 */
	private final Set<Class<? extends Exception>> retryableExceptions;

	/** 失败实例摘除冷却毫秒。 */
	private final long unhealthyCooldownMs;

	/** 事件监听器。 */
	private final FailoverListener listener;

	private FailoverConfig(Builder b) {
		this.maxAttempts = b.maxAttempts;
		this.retryableExceptions = Set.copyOf(b.retryableExceptions);
		this.unhealthyCooldownMs = b.unhealthyCooldownMs;
		this.listener = b.listener;
	}

	/**
	 * 默认配置：最多 3 次、默认冷却 30s、空监听器。
	 *
	 * @return 默认配置
	 */
	public static FailoverConfig defaults() {
		return builder().build();
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
	 * 最大尝试次数。
	 *
	 * @return 次数
	 */
	public int maxAttempts() {
		return this.maxAttempts;
	}

	/**
	 * 不健康冷却毫秒。
	 *
	 * @return 毫秒
	 */
	public long unhealthyCooldownMs() {
		return this.unhealthyCooldownMs;
	}

	/**
	 * 事件监听器。
	 *
	 * @return 监听器（永不为 null）
	 */
	public FailoverListener listener() {
		return this.listener;
	}

	/**
	 * 判断异常是否应触发故障转移。
	 *
	 * @param t 异常
	 * @return 可转移返回 true
	 */
	public boolean isRetryable(Throwable t) {
		Throwable cur = t;
		while (cur != null) {
			// 4xx 不可转移；5xx 可转移
			if (cur instanceof AiApiException api) {
				int status = api.getHttpStatus();
				if (status >= 500) {
					return true;
				}
				if (status >= 400) {
					return false;
				}
			}
			if (cur instanceof AiTimeoutException) {
				return true;
			}
			for (Class<? extends Exception> c : this.retryableExceptions) {
				if (c.isInstance(cur)) {
					return true;
				}
			}
			cur = cur.getCause();
		}
		// 非 AiException（原始连接/IO 异常）视为可转移
		return !(t instanceof AiException);
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

		private Set<Class<? extends Exception>> retryableExceptions = new LinkedHashSet<>();

		private long unhealthyCooldownMs = TimeUnit.SECONDS.toMillis(DEFAULT_COOLDOWN_SECONDS);

		private FailoverListener listener = new NoopListener();

		private Builder() {
		}

		/**
		 * 最大尝试次数（≥1）。
		 *
		 * @param maxAttempts 次数
		 * @return this
		 */
		public Builder maxAttempts(int maxAttempts) {
			this.maxAttempts = maxAttempts;
			return this;
		}

		/**
		 * 额外可转移异常类型。
		 *
		 * @param type 异常类型
		 * @return this
		 */
		public Builder retryable(Class<? extends Exception> type) {
			this.retryableExceptions.add(type);
			return this;
		}

		/**
		 * 失败实例摘除冷却毫秒。
		 *
		 * @param cooldownMs 毫秒
		 * @return this
		 */
		public Builder unhealthyCooldownMs(long cooldownMs) {
			this.unhealthyCooldownMs = cooldownMs;
			return this;
		}

		/**
		 * 事件监听器。
		 *
		 * @param listener 监听器
		 * @return this
		 */
		public Builder listener(FailoverListener listener) {
			this.listener = listener == null ? new NoopListener() : listener;
			return this;
		}

		/**
		 * 构建配置。
		 *
		 * @return 配置
		 */
		public FailoverConfig build() {
			if (this.maxAttempts < 1) {
				throw new IllegalArgumentException("maxAttempts must be >= 1");
			}
			return new FailoverConfig(this);
		}
	}

	/** 空监听器。 */
	private static final class NoopListener implements FailoverListener {
		@Override
		public void onFailover(String fromPlatform, String fromInstanceId,
				String toPlatform, String toInstanceId, Exception cause, int attempt) {
			// no-op
		}

		@Override
		public void onExhausted(java.util.List<ClientCandidate> tried, Exception lastCause) {
			// no-op
		}
	}
}
