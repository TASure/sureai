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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评估阈值断言工具：用于任务成功率回归测试。
 *
 * @author sureai
 * @since 1.8.0
 */
public final class EvaluationAssertions {

	private EvaluationAssertions() {
		throw new AssertionError("No instances");
	}

	/**
	 * 断言评估结果满足全部阈值。
	 *
	 * <p>逐项核对 {@code threshold.minimums()} 中的指标：实际分数存在且 ≥ 最低分才算通过；
	 * 指标缺失或在结果中不适用（NaN）视为不通过。任一不满足即抛
	 * {@link AssertionError}，消息内含指标名、实际值与期望值。</p>
	 *
	 * @param result 评估结果
	 * @param threshold 阈值
	 * @throws AssertionError 存在不达标指标时
	 */
	public static void assertMeets(EvaluationResult result, EvaluationThreshold threshold) {
		List<String> failures = new ArrayList<>();
		for (Map.Entry<String, Double> e : threshold.minimums().entrySet()) {
			String metric = e.getKey();
			double expected = e.getValue();
			double actual = result.score(metric);
			if (Double.isNaN(actual)) {
				failures.add(metric + " 不适用/缺失（期望 ≥ " + fmt(expected) + "）");
			} else if (actual < expected) {
				failures.add(metric + " 实际 " + fmt(actual) + " < 期望 " + fmt(expected));
			}
		}
		if (!failures.isEmpty()) {
			throw new AssertionError("评估未达标：\n  - " + String.join("\n  - ", failures)
					+ "\n实际结果：\n" + result.summary());
		}
	}

	private static String fmt(double v) {
		return String.format("%.3f", v);
	}
}
