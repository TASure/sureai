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
package com.sure.ai.evals.assertion;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CI 回归阈值（不可变）：指标名 → 最低均分 / 最低通过率。
 *
 * <p>未在阈值表中的指标不约束；阈值表要求但报告中缺失或全 NaN（不适用）的指标
 * 视为不达标。风格对齐 RAG {@code EvaluationThreshold}，但同时支持均分阈值与通过率阈值。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalThreshold {

	private final Map<String, Double> minimumMeans;
	private final Map<String, Double> minimumPassRates;

	private EvalThreshold(Builder b) {
		this.minimumMeans = Map.copyOf(b.minimumMeans);
		this.minimumPassRates = Map.copyOf(b.minimumPassRates);
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
	 * 便捷构造单指标均分阈值。
	 *
	 * @param metric 指标名
	 * @param minMean 最低均分
	 * @return 阈值
	 */
	public static EvalThreshold of(String metric, double minMean) {
		return builder().putMean(metric, minMean).build();
	}

	/**
	 * 均分阈值表（指标名 → 最低均分）。
	 *
	 * @return 不可变 Map
	 */
	public Map<String, Double> minimumMeans() {
		return this.minimumMeans;
	}

	/**
	 * 通过率阈值表（指标名 → 最低通过率）。
	 *
	 * @return 不可变 Map
	 */
	public Map<String, Double> minimumPassRates() {
		return this.minimumPassRates;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private final Map<String, Double> minimumMeans = new LinkedHashMap<>();
		private final Map<String, Double> minimumPassRates = new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * 设置某指标最低均分。
		 *
		 * @param metric 指标名
		 * @param minMean 最低均分 [0,1]
		 * @return this
		 */
		public Builder putMean(String metric, double minMean) {
			this.minimumMeans.put(metric, minMean);
			return this;
		}

		/**
		 * 设置某指标最低通过率。
		 *
		 * @param metric 指标名
		 * @param minPassRate 最低通过率 [0,1]
		 * @return this
		 */
		public Builder putPassRate(String metric, double minPassRate) {
			this.minimumPassRates.put(metric, minPassRate);
			return this;
		}

		/**
		 * 构建阈值。
		 *
		 * @return 阈值
		 */
		public EvalThreshold build() {
			return new EvalThreshold(this);
		}
	}
}
