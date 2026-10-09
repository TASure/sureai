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

package com.sure.ai.client.realtime;

/**
 * 实时对话连接的运行时选项：自动重连、退避与心跳保活。
 *
 * <p>不可变值对象，通过 {@link Builder} 构造；默认值对现有行为友好：</p>
 * <ul>
 *   <li>自动重连默认开启，最多 {@code 5} 次；</li>
 *   <li>退避为指数退避：{@code min(base * 2^(attempt-1), max)}，默认 1s 起步、封顶 30s；</li>
 *   <li>心跳默认 30s 一次 ping，10s 无任何入站数据即判定连接死亡。</li>
 * </ul>
 *
 * @author sureai
 * @since 2.4.0
 */
public final class RealtimeOptions {

	/** 默认最大重连次数。 */
	public static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;

	/** 默认重连起步退避（ms）。 */
	public static final long DEFAULT_BASE_DELAY_MS = 1_000L;

	/** 默认重连退避封顶（ms）。 */
	public static final long DEFAULT_MAX_DELAY_MS = 30_000L;

	/** 默认心跳间隔（ms），{@code <=0} 表示关闭心跳。 */
	public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000L;

	/** 默认心跳超时（ms）：该时长内无任何入站帧即判定死亡。 */
	public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 10_000L;

	/** 是否自动重连。 */
	private final boolean autoReconnect;

	/** 最大重连次数。 */
	private final int maxReconnectAttempts;

	/** 重连起步退避（ms）。 */
	private final long reconnectBaseDelayMillis;

	/** 重连退避封顶（ms）。 */
	private final long reconnectMaxDelayMillis;

	/** 心跳间隔（ms），<=0 关闭。 */
	private final long heartbeatIntervalMillis;

	/** 心跳超时（ms）。 */
	private final long heartbeatTimeoutMillis;

	private RealtimeOptions(Builder b) {
		this.autoReconnect = b.autoReconnect;
		this.maxReconnectAttempts = b.maxReconnectAttempts;
		this.reconnectBaseDelayMillis = b.reconnectBaseDelayMillis;
		this.reconnectMaxDelayMillis = b.reconnectMaxDelayMillis;
		this.heartbeatIntervalMillis = b.heartbeatIntervalMillis;
		this.heartbeatTimeoutMillis = b.heartbeatTimeoutMillis;
	}

	/**
	 * 默认选项。
	 *
	 * @return 默认选项
	 */
	public static RealtimeOptions defaults() {
		return builder().build();
	}

	/**
	 * 构造器。
	 *
	 * @return 建造器
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 是否自动重连。
	 *
	 * @return 自动重连返回 true
	 */
	public boolean autoReconnect() {
		return this.autoReconnect;
	}

	/**
	 * 最大重连次数。
	 *
	 * @return 次数
	 */
	public int maxReconnectAttempts() {
		return this.maxReconnectAttempts;
	}

	/**
	 * 重连起步退避（ms）。
	 *
	 * @return 起步退避
	 */
	public long reconnectBaseDelayMillis() {
		return this.reconnectBaseDelayMillis;
	}

	/**
	 * 重连退避封顶（ms）。
	 *
	 * @return 封顶
	 */
	public long reconnectMaxDelayMillis() {
		return this.reconnectMaxDelayMillis;
	}

	/**
	 * 心跳间隔（ms），<=0 表示关闭。
	 *
	 * @return 心跳间隔
	 */
	public long heartbeatIntervalMillis() {
		return this.heartbeatIntervalMillis;
	}

	/**
	 * 心跳超时（ms）。
	 *
	 * @return 心跳超时
	 */
	public long heartbeatTimeoutMillis() {
		return this.heartbeatTimeoutMillis;
	}

	/**
	 * 指数退避计算：{@code min(base * 2^(attempt-1), max)}，attempt 从 1 起。
	 *
	 * @param attempt 第几次重连（从 1 起）
	 * @return 等待毫秒数
	 */
	public long backoffMillis(int attempt) {
		long delay = this.reconnectBaseDelayMillis;
		for (int i = 1; i < attempt; i++) {
			delay *= 2;
			if (delay >= this.reconnectMaxDelayMillis) {
				return this.reconnectMaxDelayMillis;
			}
		}
		return Math.min(delay, this.reconnectMaxDelayMillis);
	}

	/**
	 * {@link RealtimeOptions} 建造器。
	 */
	public static final class Builder {

		private boolean autoReconnect = true;

		private int maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;

		private long reconnectBaseDelayMillis = DEFAULT_BASE_DELAY_MS;

		private long reconnectMaxDelayMillis = DEFAULT_MAX_DELAY_MS;

		private long heartbeatIntervalMillis = DEFAULT_HEARTBEAT_INTERVAL_MS;

		private long heartbeatTimeoutMillis = DEFAULT_HEARTBEAT_TIMEOUT_MS;

		private Builder() {
		}

		/**
		 * 设置是否自动重连。
		 *
		 * @param autoReconnect 自动重连
		 * @return this
		 */
		public Builder autoReconnect(boolean autoReconnect) {
			this.autoReconnect = autoReconnect;
			return this;
		}

		/**
		 * 设置最大重连次数。
		 *
		 * @param maxReconnectAttempts 次数（>=0）
		 * @return this
		 */
		public Builder maxReconnectAttempts(int maxReconnectAttempts) {
			this.maxReconnectAttempts = Math.max(0, maxReconnectAttempts);
			return this;
		}

		/**
		 * 设置重连起步退避。
		 *
		 * @param baseDelayMillis 起步退避（>=0）
		 * @return this
		 */
		public Builder reconnectBaseDelayMillis(long baseDelayMillis) {
			this.reconnectBaseDelayMillis = Math.max(0, baseDelayMillis);
			return this;
		}

		/**
		 * 设置重连退避封顶。
		 *
		 * @param maxDelayMillis 封顶（>=0）
		 * @return this
		 */
		public Builder reconnectMaxDelayMillis(long maxDelayMillis) {
			this.reconnectMaxDelayMillis = Math.max(0, maxDelayMillis);
			return this;
		}

		/**
		 * 设置心跳间隔，<=0 关闭心跳。
		 *
		 * @param heartbeatIntervalMillis 间隔
		 * @return this
		 */
		public Builder heartbeatIntervalMillis(long heartbeatIntervalMillis) {
			this.heartbeatIntervalMillis = heartbeatIntervalMillis;
			return this;
		}

		/**
		 * 设置心跳超时。
		 *
		 * @param heartbeatTimeoutMillis 超时
		 * @return this
		 */
		public Builder heartbeatTimeoutMillis(long heartbeatTimeoutMillis) {
			this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
			return this;
		}

		/**
		 * 构建。
		 *
		 * @return 选项
		 */
		public RealtimeOptions build() {
			return new RealtimeOptions(this);
		}
	}
}
