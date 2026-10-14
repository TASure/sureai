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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sure.ai.client.AiClient;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.EvalContext;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.TokenUsage;
import com.sure.tool.lang.Assert;

/**
 * 评估执行器：批量跑数据集 → 逐用例调用被测模型 → 逐指标打分 → 聚合出 {@link EvalReport}。
 *
 * <p>失败隔离：单条用例模型调用异常只记为该用例失败（{@code succeeded=false}），
 * 不中断整批；单个指标抛异常只记为该指标 notApplicable，不影响其它指标与用例。</p>
 *
 * <p>并发：默认顺序执行；{@code concurrency(n)} 创建内部固定线程池并由 runner 生命周期
 * 管理（{@link #close()} 关闭）；{@code executorService(...)} 注入外部线程池则不关闭。
 * 无论并发与否，明细与统计均按数据集原始顺序排列，统计不依赖执行顺序。</p>
 *
 * <p>录制扩展点：{@link Builder#addListener(EvalRunListener)} 注册用例级钩子，批2 录制回放
 * 客户端据此落盘请求 / 响应。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalRunner implements AutoCloseable {

	private final AiClient client;
	private final String model;
	private final List<EvalMetric> metrics;
	private final ExecutorService executor;
	private final boolean closeExecutor;
	private final List<EvalRunListener> listeners;

	private EvalRunner(Builder b) {
		this.client = b.client;
		this.model = b.model;
		this.metrics = List.copyOf(b.metrics);
		this.executor = b.executor;
		this.closeExecutor = b.closeExecutor;
		this.listeners = List.copyOf(b.listeners);
	}

	/**
	 * 创建 Builder。
	 *
	 * @param client 被测对话客户端
	 * @param model  被测模型名
	 * @return Builder
	 */
	public static Builder builder(AiClient client, String model) {
		Assert.notNull(client, "client 不能为 null");
		Assert.notBlank(model, "model 不能为空白");
		return new Builder().client(client).model(model);
	}

	/**
	 * 执行整批评估。
	 *
	 * @param dataset 数据集
	 * @return 报告
	 */
	public EvalReport run(EvalDataset dataset) {
		Assert.notNull(dataset, "dataset 不能为 null");
		long startedAt = System.currentTimeMillis();
		EvalContext context = EvalContext.create(this.model);
		List<EvalCase> cases = dataset.cases();
		List<CaseDetail> details = new ArrayList<>(cases.size());
		if (this.executor == null) {
			for (EvalCase evalCase : cases) {
				details.add(executeOne(evalCase, context));
			}
		} else {
			List<Future<CaseDetail>> futures = new ArrayList<>(cases.size());
			for (EvalCase evalCase : cases) {
				futures.add(this.executor.submit(() -> executeOne(evalCase, context)));
			}
			for (Future<CaseDetail> future : futures) {
				try {
					details.add(future.get());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new EvalException("用例执行被中断", e);
				} catch (ExecutionException e) {
					throw new EvalException("用例执行异常", e);
				}
			}
		}
		long finishedAt = System.currentTimeMillis();
		return buildReport(dataset, startedAt, finishedAt, details);
	}

	private CaseDetail executeOne(EvalCase evalCase, EvalContext context) {
		fireStarted(evalCase);
		long t0 = System.nanoTime();
		EvalOutcome outcome;
		try {
			ChatRequest request = ChatRequest.builder()
				.model(this.model)
				.messages(evalCase.input())
				.build();
			ChatResponse response = this.client.chat(request);
			outcome = EvalOutcome.success(evalCase, response, System.nanoTime() - t0);
		} catch (RuntimeException e) {
			outcome = EvalOutcome.failure(evalCase, e, System.nanoTime() - t0);
		}
		Map<String, MetricResult> results = new LinkedHashMap<>();
		for (EvalMetric metric : this.metrics) {
			try {
				MetricResult result = metric.evaluate(evalCase, outcome, context);
				results.put(metric.name(), result == null
					? MetricResult.notApplicable("指标返回空结果")
					: result);
			} catch (RuntimeException e) {
				results.put(metric.name(),
					MetricResult.notApplicable("指标异常: " + e.getClass().getSimpleName()));
			}
		}
		fireComplete(evalCase, outcome, results);
		TokenUsage usage = outcome.usage();
		return new CaseDetail(evalCase.id(), evalCase.tags(), outcome.succeeded(),
			outcome.succeeded() ? null : errorText(outcome.error()),
			outcome.latencyNanos(),
			usage == null ? 0 : usage.promptTokens(),
			usage == null ? 0 : usage.completionTokens(),
			usage == null ? 0 : usage.totalTokens(),
			outcome.actualText(), results);
	}

	private static String errorText(Throwable error) {
		if (error == null) {
			return null;
		}
		String message = error.getMessage();
		return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}

	private void fireStarted(EvalCase evalCase) {
		for (EvalRunListener listener : this.listeners) {
			try {
				listener.onCaseStarted(evalCase);
			} catch (RuntimeException ignored) {
				// 钩子异常不影响主流程
			}
		}
	}

	private void fireComplete(EvalCase evalCase, EvalOutcome outcome,
			Map<String, MetricResult> results) {
		for (EvalRunListener listener : this.listeners) {
			try {
				listener.onCaseComplete(evalCase, outcome, results);
			} catch (RuntimeException ignored) {
				// 钩子异常不影响主流程
			}
		}
	}

	private EvalReport buildReport(EvalDataset dataset, long startedAt, long finishedAt,
			List<CaseDetail> details) {
		Map<String, MetricStats> stats = new LinkedHashMap<>();
		for (EvalMetric metric : this.metrics) {
			String name = metric.name();
			double threshold = metric.passThreshold();
			double sum = 0.0;
			double min = Double.NaN;
			double max = Double.NaN;
			int applicable = 0;
			int nan = 0;
			int pass = 0;
			for (CaseDetail detail : details) {
				MetricResult result = detail.metrics().get(name);
				if (result == null || !result.applicable()) {
					nan++;
					continue;
				}
				applicable++;
				double score = result.score();
				sum += score;
				min = Double.isNaN(min) ? score : Math.min(min, score);
				max = Double.isNaN(max) ? score : Math.max(max, score);
				if (result.passedAt(threshold)) {
					pass++;
				}
			}
			double mean = applicable == 0 ? Double.NaN : sum / applicable;
			double passRate = applicable == 0 ? Double.NaN : (double) pass / applicable;
			stats.put(name, new MetricStats(name, applicable, nan, mean, min, max, pass, passRate));
		}
		long prompt = 0;
		long completion = 0;
		long total = 0;
		long failed = 0;
		List<Double> latencies = new ArrayList<>();
		for (CaseDetail detail : details) {
			prompt += detail.promptTokens();
			completion += detail.completionTokens();
			total += detail.totalTokens();
			if (!detail.succeeded()) {
				failed++;
			}
			latencies.add(detail.latencyNanos() / 1_000_000.0);
		}
		return EvalReport.builder()
			.datasetName(dataset.name())
			.model(this.model)
			.startedAtMillis(startedAt)
			.finishedAtMillis(finishedAt)
			.details(details)
			.statsByMetric(stats)
			.tokens(prompt, completion, total)
			.failedCases(failed)
			.latency(percentile(latencies, 50), percentile(latencies, 95))
			.build();
	}

	private static double percentile(List<Double> sorted, double p) {
		if (sorted.isEmpty()) {
			return 0.0;
		}
		List<Double> values = new ArrayList<>(sorted);
		values.sort(Comparator.naturalOrder());
		int rank = (int) Math.ceil(p / 100.0 * values.size());
		rank = Math.max(1, Math.min(values.size(), rank));
		return values.get(rank - 1);
	}

	/**
	 * 关闭 runner：若线程池由本 runner 创建则关闭之；外部注入的线程池不关闭。
	 */
	@Override
	public void close() {
		if (this.closeExecutor && this.executor != null) {
			this.executor.shutdown();
			try {
				if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
					this.executor.shutdownNow();
				}
			} catch (InterruptedException e) {
				this.executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private AiClient client;
		private String model;
		private final List<EvalMetric> metrics = new ArrayList<>();
		private ExecutorService executor;
		private boolean closeExecutor;
		private final List<EvalRunListener> listeners = new ArrayList<>();

		private Builder() {
		}

		private Builder client(AiClient client) {
			this.client = client;
			return this;
		}

		private Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 追加指标（可多次调用）。
		 *
		 * @param metric 指标
		 * @return this
		 */
		public Builder addMetric(EvalMetric metric) {
			Assert.notNull(metric, "metric 不能为 null");
			this.metrics.add(metric);
			return this;
		}

		/**
		 * 批量设置指标。
		 *
		 * @param metricList 指标列表
		 * @return this
		 */
		public Builder metrics(List<EvalMetric> metricList) {
			Assert.notNull(metricList, "metricList 不能为 null");
			this.metrics.clear();
			this.metrics.addAll(metricList);
			return this;
		}

		/**
		 * 设置并发度：runner 自建固定线程池并在 {@link EvalRunner#close()} 时关闭。
		 *
		 * @param n 线程数（≤1 退化为顺序执行）
		 * @return this
		 */
		public Builder concurrency(int n) {
			if (n > 1) {
				this.executor = Executors.newFixedThreadPool(n);
				this.closeExecutor = true;
			}
			return this;
		}

		/**
		 * 注入外部线程池：runner 不负责关闭。
		 *
		 * @param executor 线程池
		 * @return this
		 */
		public Builder executorService(ExecutorService executor) {
			this.executor = executor;
			this.closeExecutor = false;
			return this;
		}

		/**
		 * 注册运行监听器（录制扩展点）。
		 *
		 * @param listener 监听器
		 * @return this
		 */
		public Builder addListener(EvalRunListener listener) {
			Assert.notNull(listener, "listener 不能为 null");
			this.listeners.add(listener);
			return this;
		}

		/**
		 * 构建执行器。
		 *
		 * @return 执行器
		 */
		public EvalRunner build() {
			Assert.notNull(this.client, "client 不能为 null");
			Assert.notBlank(this.model, "model 不能为空白");
			return new EvalRunner(this);
		}
	}
}
