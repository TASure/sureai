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

package com.sure.ai.otel.langfuse;

import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 把一次评估 run（{@link EvalReport}）导出为 Langfuse 的一个 {@code trace} + 若干 {@code score}。
 *
 * <p><b>映射关系</b>（Langfuse Ingestion v3 事件信封，见
 * <a href="https://langfuse.com/docs/tracing-features/scores">Scores 文档</a>）：</p>
 * <ul>
 *   <li>一个 {@code trace-create}：代表评估 run。id 稳定为
 *       {@code eval-<dataset>-<startedAtMillis>}（同一次 run 重跑/重导可幂等覆盖）；
 *       name 为 {@code eval:<dataset>}；metadata 记录 model / caseCount / failedCaseCount /
 *       totalTokens；environment 来自配置。</li>
 *   <li><b>每指标一个 {@code score-create}</b>（挂在该 eval trace 上）：
 *       body = {@code {id, traceId, name=<metric>, value=<聚合均值 number>,
 *       dataType="NUMERIC", comment=<passRate/applicable/nan 摘要>}}。
 *       均值为 {@link Double#NaN}（无适用用例）的指标<b>不</b>产出 score。</li>
 *   <li><b>补充数值型 score</b>（除逐指标均值外，额外三个 NUMERIC score）：
 *       {@code pass_rate}（跨指标 totalPassCount/totalApplicableCount）、
 *       {@code total_tokens}（{@link EvalReport#totalTokens()}）、
 *       {@code failed_case_count}（{@link EvalReport#failedCaseCount()}，数值型）。
 *       {@code pass_rate} 在无任何适用用例时（NaN）跳过。</li>
 * </ul>
 *
 * <p>整批（1 个 trace-create + N 个 score-create）组装为一个 {@code {"batch":[...]}} 经
 * {@link LangfuseBatchSender} 单次发送。JSON 转义（{@link #json(String)}）与信封拼装
 * 自包含于本类，不改动既有 {@link LangfuseMetricsCollector} 的签名。</p>
 *
 * <p><b>无感降级</b>：配置未启用（密钥缺失）或 report 为 {@code null} 时 {@link #export} 直接返回
 * 已完成 future、不发任何请求；发送异常/非 2xx 由 {@link LangfuseIngestionClient} 记录 warning 并吞咽，
 * 本类自身任何异常也只记 warning、绝不向上抛出，主流程零感知。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalReportLangfuseExporter {

	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

	private static final Logger LOG = Logger.getLogger(EvalReportLangfuseExporter.class.getName());

	/** score 数据类型：数值。 */
	static final String DATA_TYPE_NUMERIC = "NUMERIC";
	/** 补充 score 名：整体通过率。 */
	static final String SCORE_PASS_RATE = "pass_rate";
	/** 补充 score 名：总 token。 */
	static final String SCORE_TOTAL_TOKENS = "total_tokens";
	/** 补充 score 名：失败用例数。 */
	static final String SCORE_FAILED_CASES = "failed_case_count";

	private final boolean enabled;
	private final LangfuseBatchSender sender;
	private final String environment;

	/**
	 * 以配置构造（内部建立真实 HTTP 发送器）。
	 *
	 * @param config Langfuse 配置；禁用态下为空操作
	 */
	public EvalReportLangfuseExporter(LangfuseConfig config) {
		this(config != null && config.isEnabled(),
			config == null || !config.isEnabled()
				? LangfuseBatchSender.noop()
				: new LangfuseIngestionClient(config),
			config == null ? null : config.environment());
	}

	/**
	 * 测试/高级构造：注入启用标志与发送桩。
	 *
	 * @param enabled     是否启用
	 * @param sender     批次发送器
	 * @param environment 环境标签；可空
	 */
	EvalReportLangfuseExporter(boolean enabled, LangfuseBatchSender sender, String environment) {
		this.enabled = enabled;
		this.sender = sender;
		this.environment = environment;
	}

	/**
	 * 导出一次评估 run 为 Langfuse trace + scores。
	 *
	 * @param report 评估报告；为 {@code null} 或本实例禁用时空操作
	 * @return 发送完成句柄（异常已被内部记录并吞咽）
	 */
	public CompletableFuture<Void> export(EvalReport report) {
		if (!this.enabled || report == null) {
			return CompletableFuture.completedFuture(null);
		}
		try {
			List<String> batch = buildBatch(report);
			return this.sender.send(batch);
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "langfuse eval export error: " + ex.getMessage(), ex);
			return CompletableFuture.completedFuture(null);
		}
	}

	/** 组装 1 个 trace-create + 逐指标/补充 score-create。 */
	private List<String> buildBatch(EvalReport report) {
		long epochMs = report.startedAtMillis() > 0L ? report.startedAtMillis()
			: System.currentTimeMillis();
		String dataset = report.datasetName() == null ? "dataset" : report.datasetName();
		String traceId = "eval-" + dataset + "-" + report.startedAtMillis();

		List<String> batch = new ArrayList<>(4 + report.metricStats().size());
		batch.add(traceCreate(traceId, dataset, report, epochMs));

		int totalApplicable = 0;
		int totalPass = 0;
		for (Map.Entry<String, MetricStats> e : report.metricStats().entrySet()) {
			MetricStats stats = e.getValue();
			if (Double.isNaN(stats.mean())) {
				continue;
			}
			totalApplicable += stats.applicableCount();
			totalPass += stats.passCount();
			batch.add(scoreCreate(newScoreId(), traceId, e.getKey(), stats.mean(),
				comment(stats), epochMs));
		}

		if (totalApplicable > 0) {
			double passRate = (double) totalPass / (double) totalApplicable;
			batch.add(scoreCreate(newScoreId(), traceId, SCORE_PASS_RATE, passRate,
				"overall passRate = " + totalPass + "/" + totalApplicable, epochMs));
		}
		batch.add(scoreCreate(newScoreId(), traceId, SCORE_TOTAL_TOKENS, report.totalTokens(),
			"total tokens across all cases", epochMs));
		batch.add(scoreCreate(newScoreId(), traceId, SCORE_FAILED_CASES, report.failedCaseCount(),
			"execution-failed cases", epochMs));
		return batch;
	}

	// ---------- 事件信封构造 ----------

	private String traceCreate(String traceId, String dataset, EvalReport report, long epochMs) {
		StringBuilder body = new StringBuilder(256);
		body.append("{\"id\":").append(json(traceId))
			.append(",\"name\":").append(json("eval:" + dataset))
			.append(",\"metadata\":{\"source\":\"sureai\",\"kind\":\"evaluation\"")
			.append(",\"model\":").append(json(report.model()))
			.append(",\"dataset\":").append(json(dataset))
			.append(",\"caseCount\":").append(report.caseCount())
			.append(",\"failedCaseCount\":").append(report.failedCaseCount())
			.append(",\"totalTokens\":").append(report.totalTokens())
			.append('}');
		if (this.environment != null) {
			body.append(",\"environment\":").append(json(this.environment));
		}
		body.append('}');
		return envelope("trace-create", epochMs, body.toString());
	}

	private static String scoreCreate(String scoreId, String traceId, String name, double value,
			String comment, long epochMs) {
		String body = "{\"id\":" + json(scoreId)
			+ ",\"traceId\":" + json(traceId)
			+ ",\"name\":" + json(name)
			+ ",\"value\":" + number(value)
			+ ",\"dataType\":\"" + DATA_TYPE_NUMERIC + "\""
			+ ",\"comment\":" + json(comment) + "}";
		return envelope("score-create", epochMs, body);
	}

	/** 外层信封：{@code {"id":事件uuid,"type":...,"timestamp":...,"body":{...}}}。 */
	private static String envelope(String type, long epochMs, String bodyJson) {
		return "{\"id\":" + json(UUID.randomUUID().toString())
			+ ",\"type\":\"" + type + "\""
			+ ",\"timestamp\":" + json(ISO.format(Instant.ofEpochMilli(epochMs)))
			+ ",\"body\":" + bodyJson + "}";
	}

	/** 逐指标 score 的 comment：通过率/适用数/NaN 摘要。 */
	private static String comment(MetricStats stats) {
		return "mean=" + stats.mean()
			+ "; passRate=" + (Double.isNaN(stats.passRate()) ? "n/a" : fmt(stats.passRate()))
			+ " (" + stats.passCount() + "/" + stats.applicableCount() + " applicable)"
			+ "; nan=" + stats.nanCount();
	}

	private static String fmt(double v) {
		return String.format("%.4f", v);
	}

	private static String newScoreId() {
		return "score-" + UUID.randomUUID();
	}

	/** 序列化为 JSON 数字字面量（调用方已保证 value 有限）。 */
	private static String number(double value) {
		if (value == Math.rint(value) && !Double.isInfinite(value)) {
			return String.valueOf((long) value);
		}
		return String.valueOf(value);
	}

	/** 转义为 JSON 字符串字面量（含两端引号）。 */
	static String json(String s) {
		if (s == null) {
			return "\"\"";
		}
		StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		return sb.append('"').toString();
	}
}
