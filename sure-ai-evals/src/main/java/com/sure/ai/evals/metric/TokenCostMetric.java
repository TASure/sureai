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
package com.sure.ai.evals.metric;

import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.model.TokenUsage;

/**
 * Token / 成本运行指标：按 total tokens 归一化到 [0,1]。
 *
 * <p><b>口径说明</b>：core 不携带模型价格表，本模块以 token 用量作为成本代理——
 * 不做真实货币成本换算。归一化公式与 {@link LatencyMetric} 同形：</p>
 * <ul>
 *   <li>{@code tokens ≤ targetTokens}：得 1.0；</li>
 *   <li>{@code tokens ≥ limitTokens}：得 0.0；</li>
 *   <li>其间线性衰减。</li>
 * </ul>
 *
 * <p>默认 {@code targetTokens=500}、{@code limitTokens=2000}，通过阈值默认 0.5。
 * 响应缺 {@link TokenUsage}（执行失败或平台未返回用量）时返回
 * {@link MetricResult#notApplicable(String)}。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class TokenCostMetric implements EvalMetric {

	private final long targetTokens;
	private final long limitTokens;
	private final double passThreshold;

	private TokenCostMetric(Builder b) {
		this.targetTokens = b.targetTokens;
		this.limitTokens = b.limitTokens;
		this.passThreshold = b.passThreshold;
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String name() {
		return "token_cost";
	}

	@Override
	public double passThreshold() {
		return this.passThreshold;
	}

	@Override
	public MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context) {
		TokenUsage usage = outcome.usage();
		if (usage == null) {
			return MetricResult.notApplicable("缺 token usage");
		}
		long tokens = usage.totalTokens();
		double score;
		if (tokens <= this.targetTokens) {
			score = 1.0;
		} else if (tokens >= this.limitTokens) {
			score = 0.0;
		} else {
			score = 1.0 - (double) (tokens - this.targetTokens) / (this.limitTokens - this.targetTokens);
		}
		return MetricResult.of(score, String.format("total tokens=%d（目标 %d / 上限 %d）",
			tokens, this.targetTokens, this.limitTokens));
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private long targetTokens = 500L;
		private long limitTokens = 2000L;
		private double passThreshold = 0.5;

		private Builder() {
		}

		/**
		 * 设置达标 token 数（默认 500）。
		 *
		 * @param targetTokens 目标 token 数
		 * @return this
		 */
		public Builder targetTokens(long targetTokens) {
			this.targetTokens = targetTokens;
			return this;
		}

		/**
		 * 设置零分 token 上限（默认 2000）。
		 *
		 * @param limitTokens 上限
		 * @return this
		 */
		public Builder limitTokens(long limitTokens) {
			this.limitTokens = limitTokens;
			return this;
		}

		/**
		 * 设置通过阈值（默认 0.5）。
		 *
		 * @param passThreshold 阈值
		 * @return this
		 */
		public Builder passThreshold(double passThreshold) {
			this.passThreshold = passThreshold;
			return this;
		}

		/**
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public TokenCostMetric build() {
			return new TokenCostMetric(this);
		}
	}
}
