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

import java.util.List;
import java.util.Map;

import com.sure.ai.evals.metric.MetricResult;

/**
 * 单条用例的评估明细（不可变，数据集原始顺序）。
 *
 * @param caseId          用例 ID
 * @param tags            用例标签
 * @param succeeded       被测模型是否执行成功（false 时 errorMessage 非空）
 * @param errorMessage    执行错误信息（成功为 null）
 * @param latencyNanos    端到端耗时纳秒
 * @param promptTokens    提示 token 数（缺 usage 为 0）
 * @param completionTokens 补全 token 数（缺 usage 为 0）
 * @param totalTokens     总 token 数（缺 usage 为 0）
 * @param actualText      实际输出文本
 * @param metrics         指标名 → 结果（按指标注册顺序）
 * @author sureai
 * @since 2.8.0
 */
public record CaseDetail(String caseId, List<String> tags, boolean succeeded,
		String errorMessage, long latencyNanos, int promptTokens, int completionTokens,
		int totalTokens, String actualText, Map<String, MetricResult> metrics) {

	/**
	 * 紧凑构造器：防御性拷贝。
	 */
	public CaseDetail {
		tags = tags == null ? List.of() : List.copyOf(tags);
		metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
		actualText = actualText == null ? "" : actualText;
	}
}
