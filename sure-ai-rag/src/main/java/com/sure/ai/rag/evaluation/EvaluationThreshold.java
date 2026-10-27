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

package com.sure.ai.rag.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回归阈值：指标名 → 最低可接受分数。
 *
 * <p>用于任务成功率回归：{@link EvaluationAssertions#assertMeets} 据此判断一次评估是否达标。
 * 未在阈值表中的指标不做约束；阈值表中要求但结果中不适用（NaN）的指标视为不达标。</p>
 *
 * @param minimums 指标名 → 最低分（[0,1]）
 * @author sureai
 * @since 1.8.0
 */
public record EvaluationThreshold(Map<String, Double> minimums) {

	/**
	 * 紧凑构造器：防御性拷贝。
	 */
	public EvaluationThreshold {
		minimums = minimums == null ? Map.of() : Map.copyOf(minimums);
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
	 * 便捷构造单指标阈值。
	 *
	 * @param metric 指标名
	 * @param min 最低分
	 * @return 阈值
	 */
	public static EvaluationThreshold of(String metric, double min) {
		return builder().put(metric, min).build();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private final Map<String, Double> minimums = new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * 设置某指标最低分。
		 *
		 * @param metric 指标名
		 * @param min 最低分
		 * @return this
		 */
		public Builder put(String metric, double min) {
			this.minimums.put(metric, min);
			return this;
		}

		/**
		 * 构建阈值。
		 *
		 * @return 阈值
		 */
		public EvaluationThreshold build() {
			return new EvaluationThreshold(this.minimums);
		}
	}
}
