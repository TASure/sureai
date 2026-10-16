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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;

import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.Test;

/**
 * {@link OtelEvalTracing} 单测：用内存 {@link InMemorySpanExporter} 断言 {@code eval.evaluation}
 * span 名与关键属性、错误态、noop 降级，零真实网络。
 *
 * @author sureai
 * @since 2.8.0
 */
public class OtelEvalTracingTest {

	private static final long START = 1_700_000_000_000L;
	private static final long FINISH = 1_700_000_100_000L;

	private SdkTracerProvider newProvider(InMemorySpanExporter exporter) {
		return SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
	}

	/** 构造一个最小用例明细。 */
	private static CaseDetail caseDetail(String id, boolean succeeded) {
		return new CaseDetail(id, List.of(), succeeded, succeeded ? null : "boom", 1_000_000_000L,
			10, 5, 15, "answer", Map.of());
	}

	/** 成功 run：所有指标 passRate=1、无失败用例 → span 名/属性正确、状态非 ERROR。 */
	@Test
	public void testSuccessRunProducesEvaluationSpan() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = newProvider(exporter);

		EvalReport report = EvalReport.builder()
			.datasetName("gsm8k")
			.model("gpt-4o")
			.startedAtMillis(START)
			.finishedAtMillis(FINISH)
			.details(List.of(caseDetail("c1", true), caseDetail("c2", true)))
			.statsByMetric(Map.of(
				"exact_match", new MetricStats("exact_match", 10, 0, 0.85, 0.0, 1.0, 10, 1.0),
				"contains", new MetricStats("contains", 10, 0, 0.9, 0.5, 1.0, 10, 1.0)))
			.tokens(1000, 500, 1500)
			.failedCases(0)
			.latency(120.0, 350.0)
			.build();

		new OtelEvalTracing(provider).record(report);

		List<SpanData> spans = exporter.getFinishedSpanItems();
		assertEquals(1, spans.size());
		SpanData span = spans.get(0);
		assertEquals("eval.evaluation", span.getName());
		assertEquals("gsm8k", attr(span, "gen_ai.evaluation.dataset.name"));
		assertEquals("gpt-4o", attr(span, "gen_ai.request.model"));
		assertEquals(Long.valueOf(2L), longAttr(span, "gen_ai.evaluation.case_count"));
		assertEquals(Long.valueOf(0L), longAttr(span, "gen_ai.evaluation.failed_case_count"));
		assertEquals(Long.valueOf(1500L), longAttr(span, "gen_ai.evaluation.total_tokens"));
		assertEquals(0.85d, doubleAttr(span, "gen_ai.evaluation.metric.exact_match"), 1e-9);
		assertEquals(0.9d, doubleAttr(span, "gen_ai.evaluation.metric.contains"), 1e-9);
		assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
		// 起止时间戳取自报告
		assertEquals(START, span.getStartEpochNanos() / 1_000_000L);
		assertEquals(FINISH, span.getEndEpochNanos() / 1_000_000L);
	}

	/** 执行失败用例 >0 → span ERROR，error.type=eval.execution_failure。 */
	@Test
	public void testFailedCasesMarkSpanError() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = newProvider(exporter);

		EvalReport report = EvalReport.builder()
			.datasetName("ds")
			.model("m")
			.startedAtMillis(START)
			.finishedAtMillis(FINISH)
			.details(List.of(caseDetail("c1", true), caseDetail("c2", false)))
			.statsByMetric(Map.of("exact_match",
				new MetricStats("exact_match", 10, 0, 0.8, 0.0, 1.0, 10, 1.0)))
			.tokens(1, 1, 2)
			.failedCases(1)
			.latency(10.0, 20.0)
			.build();

		new OtelEvalTracing(provider).record(report);

		SpanData span = exporter.getFinishedSpanItems().get(0);
		assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
		assertEquals("eval.execution_failure", attr(span, "error.type"));
	}

	/** 无执行失败但某指标通过率<1（断言失败）→ span ERROR，error.type=eval.assertion_failure。 */
	@Test
	public void testAssertionFailureMarksSpanError() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = newProvider(exporter);

		EvalReport report = EvalReport.builder()
			.datasetName("ds")
			.model("m")
			.details(List.of(caseDetail("c1", true)))
			.statsByMetric(Map.of("exact_match",
				new MetricStats("exact_match", 10, 0, 0.8, 0.0, 1.0, 8, 0.8)))
			.tokens(1, 1, 2)
			.failedCases(0)
			.latency(10.0, 20.0)
			.build();

		new OtelEvalTracing(provider).record(report);

		SpanData span = exporter.getFinishedSpanItems().get(0);
		assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
		assertEquals("eval.assertion_failure", attr(span, "error.type"));
	}

	/** 均值 NaN 的指标跳过属性；指标名经合法字符清洗；blank 数据集名不写属性。 */
	@Test
	public void testNanMetricSkippedAndNameSanitized() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = newProvider(exporter);

		EvalReport report = EvalReport.builder()
			.datasetName("  ")
			.model("m")
			.details(List.of(caseDetail("c1", true)))
			.statsByMetric(Map.of(
				"has space&special", new MetricStats("has space&special", 5, 0, 0.7, 0.0, 1.0, 5, 1.0),
				"nan_metric", new MetricStats("nan_metric", 0, 3, Double.NaN, Double.NaN, Double.NaN, 0,
					Double.NaN)))
			.tokens(0, 0, 0)
			.failedCases(0)
			.latency(Double.NaN, Double.NaN)
			.build();

		new OtelEvalTracing(provider).record(report);

		SpanData span = exporter.getFinishedSpanItems().get(0);
		// 清洗后：空格与 & → _
		assertEquals(0.7d, doubleAttr(span, "gen_ai.evaluation.metric.has_space_special"), 1e-9);
		// NaN 指标不产生属性
		assertEquals(null, span.getAttributes()
			.get(AttributeKey.doubleKey("gen_ai.evaluation.metric.nan_metric")));
		// blank 数据集名不写属性
		assertEquals(null, span.getAttributes()
			.get(AttributeKey.stringKey("gen_ai.evaluation.dataset.name")));
		// NaN 延迟不写属性
		assertEquals(null, span.getAttributes()
			.get(AttributeKey.doubleKey("gen_ai.evaluation.latency_p50")));
	}

	/** null TracerProvider / null OpenTelemetry：全空操作不抛异常、无 span。 */
	@Test
	public void testNoopDegradation() {
		new OtelEvalTracing((io.opentelemetry.api.trace.TracerProvider) null).record(null);
		new OtelEvalTracing((io.opentelemetry.api.OpenTelemetry) null).record(
			EvalReport.builder().build());
		// 不抛异常即通过
	}

	/** 门面返回非空实例；null provider 门面不抛异常。 */
	@Test
	public void testFacade() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = newProvider(exporter);
		assertNotNull(OtelSupport.evalTracing(provider));
		assertNotNull(OtelSupport.evalTracing((io.opentelemetry.api.OpenTelemetry) null));
		OtelSupport.evalTracing((io.opentelemetry.api.trace.TracerProvider) null).record(null);
	}

	// ---------- 断言辅助 ----------

	private static String attr(SpanData span, String key) {
		return span.getAttributes().get(AttributeKey.stringKey(key));
	}

	private static Long longAttr(SpanData span, String key) {
		return span.getAttributes().get(AttributeKey.longKey(key));
	}

	private static double doubleAttr(SpanData span, String key) {
		Double v = span.getAttributes().get(AttributeKey.doubleKey(key));
		assertNotNull("missing double attr: " + key, v);
		return v;
	}
}
