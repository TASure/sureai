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

package com.sure.ai.otel;

import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 把一次评估 run（{@link EvalReport}）映射为 OpenTelemetry {@code eval.evaluation} 语义约定 span。
 *
 * <p><b>span 属性映射</b>（键名遵循 GenAI evaluation 语义约定扩展命名空间 {@code gen_ai.evaluation.*}）：</p>
 * <ul>
 *   <li>{@code gen_ai.evaluation.dataset.name} —— 数据集名（{@link EvalReport#datasetName()}）；</li>
 *   <li>{@code gen_ai.request.model} —— 被测模型（复用 {@link OtelGenAiMetrics#ATTR_REQUEST_MODEL}）；</li>
 *   <li>{@code gen_ai.evaluation.case_count} —— 用例总数；</li>
 *   <li>{@code gen_ai.evaluation.failed_case_count} —— 执行失败用例数；</li>
 *   <li>{@code gen_ai.evaluation.total_tokens} —— 总 token；</li>
 *   <li>{@code gen_ai.evaluation.latency_p50} / {@code gen_ai.evaluation.latency_p95} ——
 *       延迟分位数（毫秒，报告原值）；</li>
 *   <li>{@code gen_ai.evaluation.metric.<name>} —— 每指标聚合均值（{@link MetricStats#mean()}），
 *       动态属性名经合法字符清洗（非 {@code [A-Za-z0-9._-]} 一律替换为 {@code _}）；
 *       均值为 {@link Double#NaN}（无适用用例）时跳过该属性。</li>
 * </ul>
 *
 * <p><b>错误态</b>：当存在执行失败用例（{@link EvalReport#failedCaseCount()}&gt;0）<i>或</i>
 * 任一适用指标通过率不足 1（{@code applicableCount>0 && passRate<1}，即断言失败）时，
 * 按 OTel 约定把 span 置为 {@link StatusCode#ERROR}，并写低基数常量 {@code error.type}
 * （{@code eval.execution_failure} / {@code eval.assertion_failure}，二选一常量，杜绝高基数）。
 * span 起止时间戳尽量取报告的 {@code startedAtMillis/finishedAtMillis}，缺省（≤0）时回退墙钟时间；
 * span 一定在 {@code finally} 中 {@code end()}。</p>
 *
 * <p><b>刻意不做</b>：默认<b>不</b>按用例（case）展开子 span / span 事件——用例级明细在
 * {@code EvalReport.details()} 与 Langfuse scores / HTML-JSON-MD 报告中查看，避免评估 run 产生
 * 高基数、高属性爆炸的 span 树。如需用例级轨迹，请在评估执行链路里自行用 {@code Tracer} 包裹。</p>
 *
 * <p><b>无感降级</b>：传入 {@code null} {@link TracerProvider}（或由 {@code null}
 * {@link OpenTelemetry} 推导）时，{@link #record} 全空操作、零开销；未在 runtime 引入 OpenTelemetry SDK
 * 时本类不被加载，现有行为完全不变。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class OtelEvalTracing {

	/** 评估 run span 名。 */
	public static final String SPAN_NAME = "eval.evaluation";

	/** 数据集名属性键。 */
	static final String ATTR_EVAL_DATASET_NAME = "gen_ai.evaluation.dataset.name";
	/** 用例数属性键。 */
	static final String ATTR_EVAL_CASE_COUNT = "gen_ai.evaluation.case_count";
	/** 失败用例数属性键。 */
	static final String ATTR_EVAL_FAILED_CASE_COUNT = "gen_ai.evaluation.failed_case_count";
	/** 指标均值动态属性名前缀。 */
	static final String ATTR_EVAL_METRIC_PREFIX = "gen_ai.evaluation.metric.";
	/** 总 token 属性键。 */
	static final String ATTR_EVAL_TOTAL_TOKENS = "gen_ai.evaluation.total_tokens";
	/** p50 延迟属性键（毫秒）。 */
	static final String ATTR_EVAL_LATENCY_P50 = "gen_ai.evaluation.latency_p50";
	/** p95 延迟属性键（毫秒）。 */
	static final String ATTR_EVAL_LATENCY_P95 = "gen_ai.evaluation.latency_p95";

	/** 低基数 error.type：执行失败。 */
	static final String ERROR_TYPE_EXECUTION = "eval.execution_failure";
	/** 低基数 error.type：断言（指标阈值）失败。 */
	static final String ERROR_TYPE_ASSERTION = "eval.assertion_failure";

	private final boolean noop;
	private final Tracer tracer;

	/**
	 * 以 {@link TracerProvider} 构造。
	 *
	 * @param tracerProvider OTel TracerProvider；为 {@code null} 时全空操作
	 */
	public OtelEvalTracing(TracerProvider tracerProvider) {
		this.noop = tracerProvider == null;
		this.tracer = this.noop ? null : tracerProvider.get(OtelGenAiMetrics.INSTRUMENTATION_SCOPE);
	}

	/**
	 * 便捷构造：从 {@link OpenTelemetry} 取 {@link TracerProvider}。
	 *
	 * @param openTelemetry OTel 实例；为 {@code null} 时全空操作
	 */
	public OtelEvalTracing(OpenTelemetry openTelemetry) {
		this(openTelemetry == null ? null : openTelemetry.getTracerProvider());
	}

	/**
	 * 记录一次评估 run 为 {@code eval.evaluation} span。
	 *
	 * @param report 评估报告；为 {@code null} 或本实例为 noop 时空操作
	 */
	public void record(EvalReport report) {
		if (this.noop || report == null) {
			return;
		}
		AttributesBuilder b = Attributes.builder();
		boolean assertFailed = false;

		putIfNotBlank(b, ATTR_EVAL_DATASET_NAME, report.datasetName());
		putIfNotBlank(b, OtelGenAiMetrics.ATTR_REQUEST_MODEL, report.model());
		b.put(ATTR_EVAL_CASE_COUNT, report.caseCount());
		b.put(ATTR_EVAL_FAILED_CASE_COUNT, report.failedCaseCount());
		b.put(ATTR_EVAL_TOTAL_TOKENS, report.totalTokens());
		putDouble(b, ATTR_EVAL_LATENCY_P50, report.latencyP50());
		putDouble(b, ATTR_EVAL_LATENCY_P95, report.latencyP95());

		for (Map.Entry<String, MetricStats> e : report.metricStats().entrySet()) {
			MetricStats stats = e.getValue();
			if (Double.isNaN(stats.mean())) {
				continue;
			}
			b.put(ATTR_EVAL_METRIC_PREFIX + sanitize(e.getKey()), stats.mean());
			if (stats.applicableCount() > 0 && !Double.isNaN(stats.passRate()) && stats.passRate() < 1.0d) {
				assertFailed = true;
			}
		}

		boolean execFailed = report.failedCaseCount() > 0L;

		io.opentelemetry.api.trace.SpanBuilder sb = this.tracer.spanBuilder(SPAN_NAME)
			.setSpanKind(SpanKind.INTERNAL);
		if (report.startedAtMillis() > 0L) {
			sb.setStartTimestamp(report.startedAtMillis(), TimeUnit.MILLISECONDS);
		}
		Span span = sb.startSpan();
		try {
			span.setAllAttributes(b.build());
			if (execFailed) {
				span.setStatus(StatusCode.ERROR, "evaluation run has failed cases");
				span.setAttribute(OtelGenAiMetrics.ATTR_ERROR_TYPE, ERROR_TYPE_EXECUTION);
			} else if (assertFailed) {
				span.setStatus(StatusCode.ERROR, "evaluation run has metric assertion failures");
				span.setAttribute(OtelGenAiMetrics.ATTR_ERROR_TYPE, ERROR_TYPE_ASSERTION);
			}
		} finally {
			if (report.finishedAtMillis() > 0L) {
				span.end(report.finishedAtMillis(), TimeUnit.MILLISECONDS);
			} else {
				span.end();
			}
		}
	}

	/** 仅当字符串非空时写入。 */
	private static void putIfNotBlank(AttributesBuilder b, String key, String value) {
		if (value != null && !value.isBlank()) {
			b.put(key, value);
		}
	}

	/** 仅当 double 非 NaN 时写入。 */
	private static void putDouble(AttributesBuilder b, String key, double value) {
		if (!Double.isNaN(value)) {
			b.put(key, value);
		}
	}

	/** 把指标名清洗为合法的 OTel 属性名片段（非 {@code [A-Za-z0-9._-]} → {@code _}）。 */
	static String sanitize(String name) {
		if (name == null || name.isEmpty()) {
			return "unknown";
		}
		StringBuilder sb = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
			sb.append(ok ? c : '_');
		}
		return sb.toString();
	}
}
