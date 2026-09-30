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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.sure.ai.agent.event.AgentEventSink;
import com.sure.ai.agent.event.ThoughtEvent;
import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.Test;

/**
 * {@code sure-ai-otel} 桥接单测：用内存 MeterReader / SpanExporter 验证指标与 span 事件，零真实网络。
 *
 * @author sureai
 * @since 1.9.0
 */
public class OtelBridgeTest {

	private SdkMeterProvider newMeter(InMemoryMetricReader reader) {
		return SdkMeterProvider.builder().registerMetricReader(reader).build();
	}

	// ---------- Metrics 桥接 ----------

	/** null MeterProvider（Noop 降级）：全方法调用不抛异常、无副作用。 */
	@Test
	public void testNoopMetricsDegradation() {
		MetricsCollector c = new OtelGenAiMetrics(null, "openai", "chat");
		c.onRequestStart("/chat");
		c.onRequestSuccess("/chat", 200, 120);
		c.onRequestFailure("/chat", 500, new RuntimeException("boom"), 50);
		c.onRetry("/chat", 1, 429);
		c.onTokenUsage("gpt", 10, 5, 15);
		// 不抛异常即通过
	}

	/** 成功：gen_ai.client.operation.duration 记录一次，属性 operation.name/provider.name 正确。 */
	@Test
	public void testOnRequestSuccessRecordsDuration() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		MetricsCollector c = new OtelGenAiMetrics(mp, "openai", "chat");
		c.onRequestSuccess("/chat/completions", 200, 250);

		Collection<MetricData> metrics = reader.collectAllMetrics();
		Map<String, String> attrs = new HashMap<>();
		attrs.put("gen_ai.operation.name", "chat");
		attrs.put("gen_ai.provider.name", "openai");
		assertEquals(0.25d, histogramSum(metrics, "gen_ai.client.operation.duration", attrs), 1e-9);
		assertEquals(1L, histogramCount(metrics, "gen_ai.client.operation.duration", attrs));
	}

	/** 失败带异常：error.type 取异常类简单名。 */
	@Test
	public void testOnRequestFailureWithExceptionErrorType() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		MetricsCollector c = new OtelGenAiMetrics(mp, "openai", "chat");
		c.onRequestFailure("/chat", -1, new IllegalStateException("x"), 80);

		Map<String, String> attrs = new HashMap<>();
		attrs.put("gen_ai.operation.name", "chat");
		attrs.put("error.type", "IllegalStateException");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		assertEquals(0.08d, histogramSum(metrics, "gen_ai.client.operation.duration", attrs), 1e-9);
	}

	/** 失败仅 HTTP 状态码（无异常）：error.type 取状态码字符串。 */
	@Test
	public void testOnRequestFailureWithHttpStatusErrorType() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		MetricsCollector c = new OtelGenAiMetrics(mp, "openai", "chat");
		c.onRequestFailure("/chat", 500, null, 80);

		Map<String, String> attrs = new HashMap<>();
		attrs.put("error.type", "500");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		assertEquals(0.08d, histogramSum(metrics, "gen_ai.client.operation.duration", attrs), 1e-9);
	}

	/** Token：input/output 计数器分别累计，附 model 与 modality=text。 */
	@Test
	public void testOnTokenUsageCounters() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		MetricsCollector c = new OtelGenAiMetrics(mp, "openai", "chat");
		c.onTokenUsage("gpt-4", 10, 5, 15);
		c.onTokenUsage("gpt-4", 20, 7, 27);

		Map<String, String> attrs = new HashMap<>();
		attrs.put("gen_ai.request.model", "gpt-4");
		attrs.put("gen_ai.token.modality", "text");
		attrs.put("gen_ai.operation.name", "chat");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		assertEquals(30L, counterValue(metrics, "gen_ai.client.inference.usage.input_tokens", attrs));
		assertEquals(12L, counterValue(metrics, "gen_ai.client.inference.usage.output_tokens", attrs));
	}

	/** null model：不写 request.model 属性，但计数仍正常。 */
	@Test
	public void testOnTokenUsageNullModelNoNpe() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		MetricsCollector c = new OtelGenAiMetrics(mp, "openai", "chat");
		c.onTokenUsage(null, 4, 2, 6);

		Map<String, String> attrs = new HashMap<>();
		attrs.put("gen_ai.operation.name", "chat");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		assertEquals(4L, counterValue(metrics, "gen_ai.client.inference.usage.input_tokens", attrs));
		// 不应存在带 request.model 属性的数据点
		for (MetricData md : metrics) {
			if (md.getName().equals("gen_ai.client.inference.usage.input_tokens")) {
				for (LongPointData p : md.getLongSumData().getPoints()) {
					assertNullAttr(p.getAttributes(), "gen_ai.request.model");
				}
			}
		}
	}

	/** providerName 留空：不写 gen_ai.provider.name 属性。 */
	@Test
	public void testNoProviderNameAttributeWhenBlank() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		MetricsCollector c = new OtelGenAiMetrics(mp, null, "chat");
		c.onRequestSuccess("/chat", 200, 100);

		Collection<MetricData> metrics = reader.collectAllMetrics();
		for (MetricData md : metrics) {
			if (md.getName().equals("gen_ai.client.operation.duration")) {
				for (HistogramPointData p : md.getHistogramData().getPoints()) {
					assertNullAttr(p.getAttributes(), "gen_ai.provider.name");
				}
			}
		}
	}

	/** 自定义 operationName（embeddings）：operation.name=embeddings。 */
	@Test
	public void testCustomOperationName() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		MetricsCollector c = new OtelGenAiMetrics(mp, "openai", "embeddings");
		c.onRequestSuccess("/embeddings", 200, 30);

		Map<String, String> attrs = new HashMap<>();
		attrs.put("gen_ai.operation.name", "embeddings");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		assertEquals(0.03d, histogramSum(metrics, "gen_ai.client.operation.duration", attrs), 1e-9);
	}

	// ---------- Retry 桥接 ----------

	/** onRetry：重试计数器 +1，error.type 取状态码，exhausted=false。 */
	@Test
	public void testRetryListenerOnRetry() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		RetryListener l = new OtelRetryListener(mp, "openai", "chat");
		l.onRetry(1, 429, null, 200, "/chat");

		Map<String, String> attrs = new HashMap<>();
		attrs.put("error.type", "429");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		LongPointData p = findLongPoint(metrics, "sureai.client.retries", attrs);
		assertEquals(1L, p.getValue());
		assertEquals(Boolean.FALSE,
			p.getAttributes().get(AttributeKey.booleanKey("sureai.retry.exhausted")));
	}

	/** onRetryExhausted：计数器 +1 且 exhausted=true。 */
	@Test
	public void testRetryListenerOnExhausted() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		RetryListener l = new OtelRetryListener(mp, "openai", "chat");
		l.onRetryExhausted(3, -1, new java.io.IOException("io"), "/chat");

		Map<String, String> attrs = new HashMap<>();
		attrs.put("error.type", "IOException");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		LongPointData p = findLongPoint(metrics, "sureai.client.retries", attrs);
		assertEquals(1L, p.getValue());
		assertEquals(Boolean.TRUE,
			p.getAttributes().get(AttributeKey.booleanKey("sureai.retry.exhausted")));
	}

	/** null MeterProvider：retry 全空操作不抛异常。 */
	@Test
	public void testRetryListenerNoopDegradation() {
		RetryListener l = new OtelRetryListener(null);
		l.onRetry(1, 429, null, 100, "/chat");
		l.onRetryExhausted(2, 500, null, "/chat");
	}

	// ---------- AgentEvent 桥接 ----------

	/** 有录制中的 span：AgentEvent 被记为 span 事件，事件名与 agent.id 正确。 */
	@Test
	public void testAgentEventRecordedOnCurrentSpan() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
		Tracer tracer = tracerProvider.get("sureai-test");

		AgentEventSink sink = new OtelAgentEventSink();
		Span span = tracer.spanBuilder("agent-run").startSpan();
		try (Scope scope = span.makeCurrent()) {
			sink.onEvent(new ThoughtEvent("agent-1", "let me think", 1_000L));
		} finally {
			span.end();
		}

		List<SpanData> spans = exporter.getFinishedSpanItems();
		assertEquals(1, spans.size());
		List<EventData> events = spans.get(0).getEvents();
		assertEquals(1, events.size());
		assertEquals("thought", events.get(0).getName());
		assertEquals("agent-1",
			events.get(0).getAttributes().get(AttributeKey.stringKey("agent.id")));
	}

	/** 无录制中 span：事件桥接直接跳过，不抛异常。 */
	@Test
	public void testAgentEventNoRecordingSpanNoThrow() {
		AgentEventSink sink = new OtelAgentEventSink();
		// 无 active span：Span.current() 为 invalid，isRecording()==false
		sink.onEvent(new ThoughtEvent("agent-1", "hi", 1_000L));
		sink.onEvent(null);
	}

	// ---------- OtelSupport 门面 ----------

	/** 门面返回非空实例；null provider 时为空操作实现。 */
	@Test
	public void testOtelSupportFacade() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = newMeter(reader);
		assertNotNull(OtelSupport.metricsCollector(mp));
		assertNotNull(OtelSupport.metricsCollector(mp, "openai", "chat"));
		assertNotNull(OtelSupport.retryListener(mp));
		assertNotNull(OtelSupport.retryListener(mp, "openai", "chat"));
		assertNotNull(OtelSupport.agentEventSink());

		// null provider 门面：不抛异常
		MetricsCollector c = OtelSupport.metricsCollector(null, "openai", "chat");
		c.onTokenUsage("gpt", 1, 1, 2);
		RetryListener l = OtelSupport.retryListener(null);
		l.onRetry(1, 500, null, 1, "/x");
	}

	// ---------- 断言辅助 ----------

	private static long counterValue(Collection<MetricData> metrics, String name, Map<String, String> attrs) {
		return findLongPoint(metrics, name, attrs).getValue();
	}

	private static LongPointData findLongPoint(Collection<MetricData> metrics, String name,
			Map<String, String> attrs) {
		for (MetricData md : metrics) {
			if (md.getName().equals(name)) {
				for (LongPointData p : md.getLongSumData().getPoints()) {
					if (matches(p.getAttributes(), attrs)) {
						return p;
					}
				}
			}
		}
		fail("counter not found: " + name + " attrs=" + attrs);
		return null;
	}

	private static double histogramSum(Collection<MetricData> metrics, String name, Map<String, String> attrs) {
		for (MetricData md : metrics) {
			if (md.getName().equals(name)) {
				for (HistogramPointData p : md.getHistogramData().getPoints()) {
					if (matches(p.getAttributes(), attrs)) {
						return p.getSum();
					}
				}
			}
		}
		fail("histogram not found: " + name + " attrs=" + attrs);
		return 0d;
	}

	private static long histogramCount(Collection<MetricData> metrics, String name,
			Map<String, String> attrs) {
		for (MetricData md : metrics) {
			if (md.getName().equals(name)) {
				for (HistogramPointData p : md.getHistogramData().getPoints()) {
					if (matches(p.getAttributes(), attrs)) {
						return p.getCount();
					}
				}
			}
		}
		fail("histogram not found: " + name);
		return 0L;
	}

	private static boolean matches(io.opentelemetry.api.common.Attributes actual,
			Map<String, String> expected) {
		for (Map.Entry<String, String> e : expected.entrySet()) {
			String v = actual.get(AttributeKey.stringKey(e.getKey()));
			if (!e.getValue().equals(v)) {
				return false;
			}
		}
		return true;
	}

	private static void assertNullAttr(io.opentelemetry.api.common.Attributes attrs, String key) {
		assertTrue("attribute should be absent: " + key,
			attrs.get(AttributeKey.stringKey(key)) == null);
	}
}
