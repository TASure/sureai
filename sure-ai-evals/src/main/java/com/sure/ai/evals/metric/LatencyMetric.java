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

/**
 * 延迟运行指标：把 {@link EvalOutcome#latencyNanos()} 归一化为 [0,1] 分数。
 *
 * <p>归一化公式（{@code ms} = 耗时毫秒）：</p>
 * <ul>
 *   <li>{@code ms ≤ targetMs}：得 1.0；</li>
 *   <li>{@code ms ≥ limitMs}：得 0.0；</li>
 *   <li>其间线性衰减：{@code score = 1 - (ms - targetMs) / (limitMs - targetMs)}。</li>
 * </ul>
 *
 * <p>默认 {@code targetMs=1000}、{@code limitMs=3000}，通过阈值默认 0.5。该指标不依赖
 * 期望输出，任何用例都适用。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class LatencyMetric implements EvalMetric {

	private final long targetMs;
	private final long limitMs;
	private final double passThreshold;

	private LatencyMetric(Builder b) {
		this.targetMs = b.targetMs;
		this.limitMs = b.limitMs;
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
		return "latency";
	}

	@Override
	public double passThreshold() {
		return this.passThreshold;
	}

	@Override
	public MetricResult evaluate(EvalCase evalCase, EvalOutcome outcome, EvalContext context) {
		double ms = outcome.latencyNanos() / 1_000_000.0;
		double score;
		if (ms <= this.targetMs) {
			score = 1.0;
		} else if (ms >= this.limitMs) {
			score = 0.0;
		} else {
			score = 1.0 - (ms - this.targetMs) / (this.limitMs - this.targetMs);
		}
		return MetricResult.of(score, String.format("耗时 %.1f ms（目标 %d ms / 上限 %d ms）",
			ms, this.targetMs, this.limitMs));
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private long targetMs = 1000L;
		private long limitMs = 3000L;
		private double passThreshold = 0.5;

		private Builder() {
		}

		/**
		 * 设置达标延迟（毫秒，默认 1000）。
		 *
		 * @param targetMs 目标延迟
		 * @return this
		 */
		public Builder targetMs(long targetMs) {
			this.targetMs = targetMs;
			return this;
		}

		/**
		 * 设置零分延迟上限（毫秒，默认 3000）。
		 *
		 * @param limitMs 上限
		 * @return this
		 */
		public Builder limitMs(long limitMs) {
			this.limitMs = limitMs;
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
		public LatencyMetric build() {
			return new LatencyMetric(this);
		}
	}
}
