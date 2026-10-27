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
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;

import com.sure.ai.evals.report.CaseDetail;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.MetricStats;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.Test;

/**
 * {@link OtelEvalTracing} 防御分支覆盖补齐：{@link OpenTelemetry} 重载、null report、
 * sanitize 全字符类、null model、起止时间缺省回退。零真实网络。
 *
 * @author sureai
 * @since 2.8.0
 */
public class OtelEvalCoverageExtraTest {

	private static CaseDetail caseDetail() {
		return new CaseDetail("c1", List.of(), true, null, 1_000_000_000L, 1, 1, 2, "a", Map.of());
	}

	/** OpenTelemetry 重载（非空）：真正产出 span。 */
	@Test
	public void openTelemetryOverloadProducesSpan() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider sdkTracer = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
		OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(sdkTracer).build();

		EvalReport report = EvalReport.builder()
			.datasetName("ds")
			.model("m")
			.details(List.of(caseDetail()))
			.statsByMetric(Map.of("exact_match",
				new MetricStats("exact_match", 1, 0, 1.0, 1.0, 1.0, 1, 1.0)))
			.build();
		new OtelEvalTracing(otel).record(report);

		List<SpanData> spans = exporter.getFinishedSpanItems();
		assertEquals(1, spans.size());
		assertEquals("eval.evaluation", spans.get(0).getName());
	}

	/** 真实 provider + null report：空操作不抛、不产出 span。 */
	@Test
	public void nullReportWithRealProviderNoSpan() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
		new OtelEvalTracing(provider).record(null);
		assertEquals(0, exporter.getFinishedSpanItems().size());
	}

	/** null model：不写 gen_ai.request.model 属性。 */
	@Test
	public void nullModelAttrAbsent() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
		EvalReport report = EvalReport.builder()
			.datasetName("ds")
			.model((String) null)
			.details(List.of(caseDetail()))
			.statsByMetric(Map.of())
			.build();
		new OtelEvalTracing(provider).record(report);
		SpanData span = exporter.getFinishedSpanItems().get(0);
		assertNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
	}

	/** startedAt/finishedAt 均为 0：回退墙钟，span 正常 end。 */
	@Test
	public void zeroTimestampsFallbackWallClock() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
		EvalReport report = EvalReport.builder()
			.datasetName("ds")
			.model("m")
			.startedAtMillis(0)
			.finishedAtMillis(0)
			.details(List.of(caseDetail()))
			.statsByMetric(Map.of())
			.build();
		new OtelEvalTracing(provider).record(report);
		assertEquals(1, exporter.getFinishedSpanItems().size());
	}

	/** sanitize 直接覆盖：null/空/全非法字符/混合字符类。 */
	@Test
	public void sanitizeBranches() {
		assertEquals("unknown", OtelEvalTracing.sanitize(null));
		assertEquals("unknown", OtelEvalTracing.sanitize(""));
		// 全非法字符 → 一律替换为 _
		assertEquals("___", OtelEvalTracing.sanitize("???"));
		assertEquals("ABCabc_.-123_x", OtelEvalTracing.sanitize("ABCabc_.-123 x"));
		assertEquals("ok", OtelEvalTracing.sanitize("ok"));
	}
}
