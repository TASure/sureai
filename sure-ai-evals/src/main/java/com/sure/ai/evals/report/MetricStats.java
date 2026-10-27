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
package com.sure.ai.evals.report;

/**
 * 单个指标在整批数据上的聚合统计（不可变）。
 *
 * <p>NaN（{@link com.sure.ai.evals.metric.MetricResult#notApplicable(String)}）不计入
 * 均值 / 极值 / 通过率，只计入 {@link #nanCount()}。无任何适用用例时
 * {@code mean/min/max/passRate} 均为 {@link Double#NaN}。</p>
 *
 * @param name            指标名
 * @param applicableCount 适用（非 NaN）用例数
 * @param nanCount        不适用用例数
 * @param mean            平均分
 * @param min             最低分
 * @param max             最高分
 * @param passCount       达到该指标 passThreshold 的用例数
 * @param passRate        通过率 = passCount / applicableCount
 * @author sureai
 * @since 2.8.0
 */
public record MetricStats(String name, int applicableCount, int nanCount, double mean,
		double min, double max, int passCount, double passRate) {
}
