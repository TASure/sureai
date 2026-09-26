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

import java.time.Duration;

/**
 * 租户配额配置（不可变）。
 *
 * <p>任一限额字段为 {@code 0} 表示该维度<b>不限</b>。预算按 {@link #budgetPeriod()} 周期滚动——
 * 周期结束后该租户在本周期内的累计成本 / token 用量清零重新计算。</p>
 *
 * @param maxCostPerPeriod   周期内成本上限（USD），0=不限
 * @param maxTokensPerPeriod 周期内 token 上限，0=不限
 * @param rateLimitQps       每秒请求上限，0=不限
 * @param budgetPeriod       预算周期（默认 1 天）
 * @author sureai
 * @since 1.6.0
 */
public record TenantConfig(double maxCostPerPeriod, long maxTokensPerPeriod,
		int rateLimitQps, Duration budgetPeriod) {

	/** 默认预算周期：1 天。 */
	public static final Duration DEFAULT_PERIOD = Duration.ofDays(1);

	/**
	 * 紧凑构造器：默认周期、校验非负。
	 */
	public TenantConfig {
		budgetPeriod = budgetPeriod == null ? DEFAULT_PERIOD : budgetPeriod;
		if (maxCostPerPeriod < 0) {
			throw new IllegalArgumentException("maxCostPerPeriod must be >= 0");
		}
		if (maxTokensPerPeriod < 0) {
			throw new IllegalArgumentException("maxTokensPerPeriod must be >= 0");
		}
		if (rateLimitQps < 0) {
			throw new IllegalArgumentException("rateLimitQps must be >= 0");
		}
	}

	/**
	 * 完全不限额的租户配置。
	 *
	 * @return 不限额配置
	 */
	public static TenantConfig unlimited() {
		return new TenantConfig(0, 0, 0, DEFAULT_PERIOD);
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
	 * 是否对成本做了限制。
	 *
	 * @return 受限返回 true
	 */
	public boolean limitedCost() {
		return this.maxCostPerPeriod > 0;
	}

	/**
	 * 是否对 token 做了限制。
	 *
	 * @return 受限返回 true
	 */
	public boolean limitedTokens() {
		return this.maxTokensPerPeriod > 0;
	}

	/**
	 * 是否对 QPS 做了限制。
	 *
	 * @return 受限返回 true
	 */
	public boolean limitedQps() {
		return this.rateLimitQps > 0;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private double maxCostPerPeriod;
		private long maxTokensPerPeriod;
		private int rateLimitQps;
		private Duration budgetPeriod = DEFAULT_PERIOD;

		private Builder() {
		}

		/**
		 * 周期成本上限（USD）。
		 *
		 * @param maxCostPerPeriod 成本上限
		 * @return this
		 */
		public Builder maxCostPerPeriod(double maxCostPerPeriod) {
			this.maxCostPerPeriod = maxCostPerPeriod;
			return this;
		}

		/**
		 * 周期 token 上限。
		 *
		 * @param maxTokensPerPeriod token 上限
		 * @return this
		 */
		public Builder maxTokensPerPeriod(long maxTokensPerPeriod) {
			this.maxTokensPerPeriod = maxTokensPerPeriod;
			return this;
		}

		/**
		 * QPS 上限。
		 *
		 * @param rateLimitQps 每秒请求数
		 * @return this
		 */
		public Builder rateLimitQps(int rateLimitQps) {
			this.rateLimitQps = rateLimitQps;
			return this;
		}

		/**
		 * 预算周期。
		 *
		 * @param budgetPeriod 周期
		 * @return this
		 */
		public Builder budgetPeriod(Duration budgetPeriod) {
			this.budgetPeriod = budgetPeriod;
			return this;
		}

		/**
		 * 构建。
		 *
		 * @return 租户配置
		 */
		public TenantConfig build() {
			return new TenantConfig(this.maxCostPerPeriod, this.maxTokensPerPeriod,
					this.rateLimitQps, this.budgetPeriod);
		}
	}
}
