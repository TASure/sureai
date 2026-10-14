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
package com.sure.ai.evals;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.evals.assertion.EvalAssertions;
import com.sure.ai.evals.assertion.EvalThreshold;
import com.sure.ai.evals.dataset.CsvDatasetLoader;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.dataset.JsonlDatasetLoader;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.report.EvalReport;

/**
 * 评估一站式静态入口：快速构造数据集 / 加载器 / 执行器 / 跑评估 / 断言。
 *
 * <p>风格对齐各平台 {@code XxxUtil}：无状态静态方法 + {@link SingletonHolder} 持有
 * 可选的默认执行器（{@link #initDefaultRunner(EvalRunner)} 配置后，
 * {@link #defaultRunner()} 复用，{@link #clearDefaultRunner()} 重置）。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalUtil {

	private static final SingletonHolder<EvalRunner> DEFAULT_RUNNER = new SingletonHolder<>(null);

	private EvalUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 快速构造数据集。
	 *
	 * @param name  数据集名
	 * @param cases 用例
	 * @return 数据集
	 */
	public static EvalDataset dataset(String name, List<EvalCase> cases) {
		return EvalDataset.builder().name(name).cases(cases).build();
	}

	/**
	 * 快速构造数据集。
	 *
	 * @param name  数据集名
	 * @param cases 用例
	 * @return 数据集
	 */
	public static EvalDataset dataset(String name, EvalCase... cases) {
		return dataset(name, List.of(cases));
	}

	/**
	 * 默认 CSV 加载器。
	 *
	 * @return 加载器
	 */
	public static CsvDatasetLoader csvLoader() {
		return new CsvDatasetLoader();
	}

	/**
	 * 默认 JSONL 加载器。
	 *
	 * @return 加载器
	 */
	public static JsonlDatasetLoader jsonlLoader() {
		return new JsonlDatasetLoader();
	}

	/**
	 * 快速构造执行器（顺序执行）。
	 *
	 * @param client 被测客户端
	 * @param model  模型名
	 * @param metrics 指标
	 * @return 执行器
	 */
	public static EvalRunner runner(AiClient client, String model, List<EvalMetric> metrics) {
		EvalRunner.Builder builder = EvalRunner.builder(client, model);
		for (EvalMetric metric : metrics) {
			builder.addMetric(metric);
		}
		return builder.build();
	}

	/**
	 * 快速构造执行器（顺序执行）。
	 *
	 * @param client  被测客户端
	 * @param model   模型名
	 * @param metrics 指标
	 * @return 执行器
	 */
	public static EvalRunner runner(AiClient client, String model, EvalMetric... metrics) {
		return runner(client, model, List.of(metrics));
	}

	/**
	 * 一站式跑评估：构造执行器 → 执行 → 关闭执行器 → 返回报告。
	 *
	 * @param client  被测客户端
	 * @param model   模型名
	 * @param dataset 数据集
	 * @param metrics 指标
	 * @return 报告
	 */
	public static EvalReport run(AiClient client, String model, EvalDataset dataset,
			List<EvalMetric> metrics) {
		try (EvalRunner runner = runner(client, model, metrics)) {
			return runner.run(dataset);
		}
	}

	/**
	 * 一站式跑评估。
	 *
	 * @param client  被测客户端
	 * @param model   模型名
	 * @param dataset 数据集
	 * @param metrics 指标
	 * @return 报告
	 */
	public static EvalReport run(AiClient client, String model, EvalDataset dataset,
			EvalMetric... metrics) {
		List<EvalMetric> list = new ArrayList<>(List.of(metrics));
		return run(client, model, dataset, list);
	}

	/**
	 * 断言报告满足阈值（转发 {@link EvalAssertions#assertMeets}）。
	 *
	 * @param report   报告
	 * @param threshold 阈值
	 */
	public static void assertMeets(EvalReport report, EvalThreshold threshold) {
		EvalAssertions.assertMeets(report, threshold);
	}

	/**
	 * 配置默认执行器。
	 *
	 * @param runner 默认执行器
	 */
	public static void initDefaultRunner(EvalRunner runner) {
		DEFAULT_RUNNER.set(runner);
	}

	/**
	 * 取默认执行器。
	 *
	 * @return 默认执行器
	 * @throws IllegalStateException 尚未配置时抛出
	 */
	public static EvalRunner defaultRunner() {
		return DEFAULT_RUNNER.get();
	}

	/**
	 * 清除默认执行器。
	 */
	public static void clearDefaultRunner() {
		DEFAULT_RUNNER.reset();
	}
}
