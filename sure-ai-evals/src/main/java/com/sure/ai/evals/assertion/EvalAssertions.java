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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

/**
 * 评估阈值断言工具：CI 回归门禁。
 *
 * <p>逐项核对 {@link EvalThreshold}：实际均分 / 通过率存在且达标才算通过；
 * 指标缺失或全 NaN（不适用）按不通过处理。任一不满足即抛 {@link AssertionError}，
 * 消息含指标名、实际值、期望值与失败用例明细。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalAssertions {

	private EvalAssertions() {
		throw new AssertionError("No instances");
	}

	/**
	 * 断言报告满足全部阈值。
	 *
	 * @param report   评估报告
	 * @param threshold 阈值
	 * @throws AssertionError 存在不达标指标时
	 */
	public static void assertMeets(EvalReport report, EvalThreshold threshold) {
		List<String> failures = new ArrayList<>();
		for (Map.Entry<String, Double> entry : threshold.minimumMeans().entrySet()) {
			String metric = entry.getKey();
			double expected = entry.getValue();
			MetricStats stats = report.metricStats(metric);
			double actual = stats == null ? Double.NaN : stats.mean();
			if (Double.isNaN(actual)) {
				failures.add(metric + " 均分缺失/不适用（期望 ≥ " + fmt(expected) + "）");
			} else if (actual < expected) {
				failures.add(metric + " 均分 " + fmt(actual) + " < 期望 " + fmt(expected));
			}
		}
		for (Map.Entry<String, Double> entry : threshold.minimumPassRates().entrySet()) {
			String metric = entry.getKey();
			double expected = entry.getValue();
			MetricStats stats = report.metricStats(metric);
			double actual = stats == null ? Double.NaN : stats.passRate();
			if (Double.isNaN(actual)) {
				failures.add(metric + " 通过率缺失/不适用（期望 ≥ " + fmt(expected) + "）");
			} else if (actual < expected) {
				failures.add(metric + " 通过率 " + fmt(actual) + " < 期望 " + fmt(expected));
			}
		}
		if (!failures.isEmpty()) {
			StringBuilder message = new StringBuilder("评估未达标：\n  - ");
			message.append(String.join("\n  - ", failures));
			if (!report.failedCaseDetails().isEmpty()) {
				message.append("\n失败用例：");
				for (CaseDetail detail : report.failedCaseDetails()) {
					message.append("\n  - ").append(detail.caseId()).append("：")
						.append(detail.errorMessage());
				}
			}
			throw new AssertionError(message.toString());
		}
	}

	private static String fmt(double value) {
		return String.format("%.3f", value);
	}
}
