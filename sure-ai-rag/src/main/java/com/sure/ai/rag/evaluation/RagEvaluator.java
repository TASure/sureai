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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiClient;
import com.sure.tool.lang.Assert;

/**
 * RAG 评估器：编排一组 {@link RagMetric}，对单条或批量轨迹汇总打分。
 *
 * <p>默认指标集（{@link #llmDefault(AiClient, String)}）：faithfulness、context_precision、
 * context_recall、answer_relevancy。综合分 overall 为所有「适用」指标分数的算术均值；
 * 返回 NaN 的指标归入 notApplicable，不计入均值。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class RagEvaluator {

	private final List<RagMetric> metrics;

	private RagEvaluator(List<RagMetric> metrics) {
		this.metrics = List.copyOf(metrics);
	}

	/**
	 * 使用默认四指标构建评估器（LLM 直接评答案相关性，不启用 embedding 增强）。
	 *
	 * @param chatClient 对话客户端
	 * @param model 模型名
	 * @return 评估器
	 */
	public static RagEvaluator llmDefault(AiClient chatClient, String model) {
		List<RagMetric> metrics = List.of(
				FaithfulnessMetric.builder().chatClient(chatClient).model(model).build(),
				ContextPrecisionMetric.builder().chatClient(chatClient).model(model).build(),
				ContextRecallMetric.builder().chatClient(chatClient).model(model).build(),
				AnswerRelevancyMetric.builder().chatClient(chatClient).model(model).build());
		return new RagEvaluator(metrics);
	}

	/**
	 * 使用自定义指标集构建评估器。
	 *
	 * @param metrics 指标列表
	 * @return 评估器
	 */
	public static RagEvaluator of(List<RagMetric> metrics) {
		Assert.notEmpty(metrics, "metrics 不能为空");
		return new RagEvaluator(metrics);
	}

	/**
	 * 评估单条轨迹。
	 *
	 * @param trace 轨迹
	 * @return 评估结果
	 */
	public EvaluationResult evaluate(RagTrace trace) {
		Assert.notNull(trace, "trace 不能为 null");
		Map<String, Double> scores = new LinkedHashMap<>();
		List<String> notApplicable = new ArrayList<>();
		double sum = 0.0;
		int applicable = 0;
		for (RagMetric metric : this.metrics) {
			double value = metric.evaluate(trace);
			if (Double.isNaN(value)) {
				notApplicable.add(metric.name());
			} else {
				scores.put(metric.name(), value);
				sum += value;
				applicable++;
			}
		}
		double overall = applicable == 0 ? Double.NaN : sum / applicable;
		return new EvaluationResult(scores, notApplicable, overall);
	}

	/**
	 * 批量评估：对每条轨迹跑全部指标，再按指标跨轨迹取均值，最后取指标均值的均值。
	 *
	 * <p>某指标在某条轨迹上不适用（NaN）时，仅在该指标的均值中跳过该轨迹。</p>
	 *
	 * @param traces 轨迹列表
	 * @return 汇总评估结果
	 */
	public EvaluationResult evaluateAll(List<RagTrace> traces) {
		Assert.notEmpty(traces, "traces 不能为空");
		Map<String, Double> sumByMetric = new LinkedHashMap<>();
		Map<String, Integer> countByMetric = new LinkedHashMap<>();
		for (RagMetric metric : this.metrics) {
			sumByMetric.put(metric.name(), 0.0);
			countByMetric.put(metric.name(), 0);
		}
		for (RagTrace trace : traces) {
			for (RagMetric metric : this.metrics) {
				double value = metric.evaluate(trace);
				if (!Double.isNaN(value)) {
					sumByMetric.merge(metric.name(), value, Double::sum);
					countByMetric.merge(metric.name(), 1, Integer::sum);
				}
			}
		}
		Map<String, Double> scores = new LinkedHashMap<>();
		List<String> notApplicable = new ArrayList<>();
		double overallSum = 0.0;
		int applicableMetrics = 0;
		for (RagMetric metric : this.metrics) {
			String name = metric.name();
			int count = countByMetric.get(name);
			if (count == 0) {
				notApplicable.add(name);
			} else {
				double avg = sumByMetric.get(name) / count;
				scores.put(name, avg);
				overallSum += avg;
				applicableMetrics++;
			}
		}
		double overall = applicableMetrics == 0 ? Double.NaN : overallSum / applicableMetrics;
		return new EvaluationResult(scores, notApplicable, overall);
	}

	/**
	 * 当前评估器持有的指标列表（不可变副本）。
	 *
	 * @return 指标列表
	 */
	public List<RagMetric> metrics() {
		return this.metrics;
	}
}
