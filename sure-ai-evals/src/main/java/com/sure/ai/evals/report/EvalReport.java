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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次评估运行的完整报告（不可变）。
 *
 * <p>承载：数据集名、被测模型名、起止时间戳、每用例明细（数据集原始顺序）、
 * 每指标聚合统计、总 token、失败用例数、延迟 p50/p95。访问器对后续批次稳定：
 * 批2 基线回归与批3 Langfuse/OTel 映射均消费本对象。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalReport {

	private final String datasetName;
	private final String model;
	private final long startedAtMillis;
	private final long finishedAtMillis;
	private final List<CaseDetail> details;
	private final Map<String, MetricStats> statsByMetric;
	private final long totalPromptTokens;
	private final long totalCompletionTokens;
	private final long totalTokens;
	private final long failedCases;
	private final double latencyP50;
	private final double latencyP95;

	private EvalReport(Builder b) {
		this.datasetName = b.datasetName;
		this.model = b.model;
		this.startedAtMillis = b.startedAtMillis;
		this.finishedAtMillis = b.finishedAtMillis;
		this.details = List.copyOf(b.details);
		this.statsByMetric = Map.copyOf(b.statsByMetric);
		this.totalPromptTokens = b.totalPromptTokens;
		this.totalCompletionTokens = b.totalCompletionTokens;
		this.totalTokens = b.totalTokens;
		this.failedCases = b.failedCases;
		this.latencyP50 = b.latencyP50;
		this.latencyP95 = b.latencyP95;
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
	 * 数据集名。
	 *
	 * @return 名称
	 */
	public String datasetName() {
		return this.datasetName;
	}

	/**
	 * 被测模型名。
	 *
	 * @return 模型名
	 */
	public String model() {
		return this.model;
	}

	/**
	 * 开始时间戳（epoch 毫秒）。
	 *
	 * @return 毫秒
	 */
	public long startedAtMillis() {
		return this.startedAtMillis;
	}

	/**
	 * 结束时间戳（epoch 毫秒）。
	 *
	 * @return 毫秒
	 */
	public long finishedAtMillis() {
		return this.finishedAtMillis;
	}

	/**
	 * 每用例明细（数据集原始顺序，不可变）。
	 *
	 * @return 明细列表
	 */
	public List<CaseDetail> details() {
		return this.details;
	}

	/**
	 * 按用例 ID 查明细，找不到返回 null。
	 *
	 * @param caseId 用例 ID
	 * @return 明细或 null
	 */
	public CaseDetail detailByCaseId(String caseId) {
		for (CaseDetail detail : this.details) {
			if (detail.caseId().equals(caseId)) {
				return detail;
			}
		}
		return null;
	}

	/**
	 * 用例总数。
	 *
	 * @return 数量
	 */
	public int caseCount() {
		return this.details.size();
	}

	/**
	 * 按指标名取聚合统计，找不到返回 null。
	 *
	 * @param metric 指标名
	 * @return 统计或 null
	 */
	public MetricStats metricStats(String metric) {
		return this.statsByMetric.get(metric);
	}

	/**
	 * 全部指标聚合统计（指标名 → 统计，不可变）。
	 *
	 * @return 统计表
	 */
	public Map<String, MetricStats> metricStats() {
		return this.statsByMetric;
	}

	/**
	 * 提示 token 总数。
	 *
	 * @return token 数
	 */
	public long totalPromptTokens() {
		return this.totalPromptTokens;
	}

	/**
	 * 补全 token 总数。
	 *
	 * @return token 数
	 */
	public long totalCompletionTokens() {
		return this.totalCompletionTokens;
	}

	/**
	 * 总 token 数。
	 *
	 * @return token 数
	 */
	public long totalTokens() {
		return this.totalTokens;
	}

	/**
	 * 执行失败用例数。
	 *
	 * @return 数量
	 */
	public long failedCaseCount() {
		return this.failedCases;
	}

	/**
	 * 失败用例明细列表（不可变）。
	 *
	 * @return 失败明细
	 */
	public List<CaseDetail> failedCaseDetails() {
		List<CaseDetail> failed = new ArrayList<>();
		for (CaseDetail detail : this.details) {
			if (!detail.succeeded()) {
				failed.add(detail);
			}
		}
		return List.copyOf(failed);
	}

	/**
	 * 延迟 p50（毫秒）。
	 *
	 * @return p50
	 */
	public double latencyP50() {
		return this.latencyP50;
	}

	/**
	 * 延迟 p95（毫秒）。
	 *
	 * @return p95
	 */
	public double latencyP95() {
		return this.latencyP95;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String datasetName = "dataset";
		private String model = "unknown";
		private long startedAtMillis;
		private long finishedAtMillis;
		private List<CaseDetail> details = List.of();
		private Map<String, MetricStats> statsByMetric = Map.of();
		private long totalPromptTokens;
		private long totalCompletionTokens;
		private long totalTokens;
		private long failedCases;
		private double latencyP50;
		private double latencyP95;

		private Builder() {
		}

		/**
		 * 设置数据集名。
		 *
		 * @param datasetName 名称
		 * @return this
		 */
		public Builder datasetName(String datasetName) {
			this.datasetName = datasetName;
			return this;
		}

		/**
		 * 设置被测模型名。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 设置开始时间戳。
		 *
		 * @param startedAtMillis 毫秒
		 * @return this
		 */
		public Builder startedAtMillis(long startedAtMillis) {
			this.startedAtMillis = startedAtMillis;
			return this;
		}

		/**
		 * 设置结束时间戳。
		 *
		 * @param finishedAtMillis 毫秒
		 * @return this
		 */
		public Builder finishedAtMillis(long finishedAtMillis) {
			this.finishedAtMillis = finishedAtMillis;
			return this;
		}

		/**
		 * 设置用例明细。
		 *
		 * @param details 明细
		 * @return this
		 */
		public Builder details(List<CaseDetail> details) {
			this.details = List.copyOf(details);
			return this;
		}

		/**
		 * 设置指标聚合统计。
		 *
		 * @param statsByMetric 统计表
		 * @return this
		 */
		public Builder statsByMetric(Map<String, MetricStats> statsByMetric) {
			this.statsByMetric = Map.copyOf(statsByMetric);
			return this;
		}

		/**
		 * 设置 token 合计。
		 *
		 * @param prompt     提示 token
		 * @param completion 补全 token
		 * @param total      总 token
		 * @return this
		 */
		public Builder tokens(long prompt, long completion, long total) {
			this.totalPromptTokens = prompt;
			this.totalCompletionTokens = completion;
			this.totalTokens = total;
			return this;
		}

		/**
		 * 设置失败用例数。
		 *
		 * @param failedCases 数量
		 * @return this
		 */
		public Builder failedCases(long failedCases) {
			this.failedCases = failedCases;
			return this;
		}

		/**
		 * 设置延迟分位数。
		 *
		 * @param p50 p50（毫秒）
		 * @param p95 p95（毫秒）
		 * @return this
		 */
		public Builder latency(double p50, double p95) {
			this.latencyP50 = p50;
			this.latencyP95 = p95;
			return this;
		}

		/**
		 * 构建报告。
		 *
		 * @return 报告
		 */
		public EvalReport build() {
			return new EvalReport(this);
		}
	}
}
