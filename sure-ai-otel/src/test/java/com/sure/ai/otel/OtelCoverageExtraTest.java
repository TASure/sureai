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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.agent.event.AgentErrorEvent;
import com.sure.ai.agent.event.AgentEvent;
import com.sure.ai.agent.event.AgentEventSink;
import com.sure.ai.agent.event.FinalAnswerEvent;
import com.sure.ai.agent.event.StepCompletedEvent;
import com.sure.ai.agent.event.StepStartedEvent;
import com.sure.ai.agent.event.ThoughtEvent;
import com.sure.ai.agent.event.ToolCalledEvent;
import com.sure.ai.agent.event.ToolCompletedEvent;
import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;
import com.sure.ai.internal.json.Json;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * {@link OtelAgentEventSink} 全事件类型桥接 + {@link OtelGenAiMetrics}/{@link OtelRetryListener}
 * 的 blank operationName 回退与 error.type="_OTHER" 分支补齐。
 *
 * <p>零真实网络，全部内存 SpanExporter / MetricReader。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class OtelCoverageExtraTest {

	/** 每种 AgentEvent 都应被记为当前 span 的事件并带 agent.id。 */
	@Test
	public void allEventTypesRecordedOnSpan() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
		Tracer tracer = tracerProvider.get("sureai-test");
		AgentEventSink sink = new OtelAgentEventSink();

		Span span = tracer.spanBuilder("run").startSpan();
		try (Scope scope = span.makeCurrent()) {
			sink.onEvent(new ThoughtEvent("agent-1", "think", 1L));
			sink.onEvent(new ToolCalledEvent("agent-2", "web", Json.object(), 2L));
			sink.onEvent(new ToolCompletedEvent("agent-3", "web", "result", true, 3L));
			sink.onEvent(new StepStartedEvent("agent-4", 1, "step", 4L));
			sink.onEvent(new StepCompletedEvent("agent-5", 1, "done", 5L));
			sink.onEvent(new FinalAnswerEvent("agent-6", "answer", 6L));
			sink.onEvent(new AgentErrorEvent("agent-7", "boom", "failure", 7L));
		} finally {
			span.end();
		}

		List<SpanData> spans = exporter.getFinishedSpanItems();
		assertEquals(1, spans.size());
		List<EventData> events = spans.get(0).getEvents();
		assertEquals(7, events.size());
		// AgentErrorEvent 额外写 error.type
		boolean errorAttrFound = false;
		for (EventData e : events) {
			String agentId = e.getAttributes().get(AttributeKey.stringKey("agent.id"));
			assertNotNull(agentId);
			String err = e.getAttributes().get(AttributeKey.stringKey("error.type"));
			if ("boom".equals(err)) {
				errorAttrFound = true;
			}
		}
		assertTrue(errorAttrFound);
	}

	/** 未知 AgentEvent 类型：resolveAgentId 返回 null，不抛异常（覆盖兜底 return null）。 */
	@Test
	public void unknownEventTypeDoesNotThrow() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
		Tracer tracer = tracerProvider.get("sureai-test");
		AgentEventSink sink = new OtelAgentEventSink();
		Span span = tracer.spanBuilder("run").startSpan();
		try (Scope scope = span.makeCurrent()) {
			sink.onEvent(new AgentEvent() {
				@Override
				public String type() {
					return "unknown.event";
				}

				@Override
				public long timestampEpochMs() {
					return 1L;
				}
			});
		} finally {
			span.end();
		}
		List<SpanData> spans = exporter.getFinishedSpanItems();
		assertEquals(1, spans.size());
	}

	/** blank operationName 回退 chat：成功耗时属性 operation.name=chat。 */
	@Test
	public void blankOperationNameFallsBackToChat() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = SdkMeterProvider.builder().registerMetricReader(reader).build();
		MetricsCollector c = new OtelGenAiMetrics(mp, "openai", "  ");
		c.onRequestSuccess("/chat", 200, 100);

		Map<String, String> attrs = new HashMap<>();
		attrs.put("gen_ai.operation.name", "chat");
		attrs.put("gen_ai.provider.name", "openai");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		boolean found = false;
		for (MetricData md : metrics) {
			if (md.getName().equals("gen_ai.client.operation.duration")) {
				found = true;
			}
		}
		assertTrue(found);
	}

	/** 失败无异常且状态码 <=0：error.type=_OTHER。 */
	@Test
	public void failureNoExceptionNoStatusUsesOther() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = SdkMeterProvider.builder().registerMetricReader(reader).build();
		MetricsCollector c = new OtelGenAiMetrics(mp, "openai", "chat");
		c.onRequestFailure("/chat", -1, null, 80);

		boolean found = false;
		for (MetricData md : reader.collectAllMetrics()) {
			if (md.getName().equals("gen_ai.client.operation.duration")) {
				found = true;
			}
		}
		assertTrue(found);
	}

	/** blank operationName 回退 chat：重试事件 operation.name=chat。 */
	@Test
	public void retryBlankOperationFallsBackToChat() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = SdkMeterProvider.builder().registerMetricReader(reader).build();
		RetryListener l = new OtelRetryListener(mp, "openai", "");
		l.onRetry(1, 429, null, 100, "/chat");

		Map<String, String> attrs = new HashMap<>();
		attrs.put("gen_ai.operation.name", "chat");
		attrs.put("error.type", "429");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		assertTrue(findLong(metrics, "sureai.client.retries", attrs) != null);
	}

	/** 重试耗尽无异常无状态码：error.type=_OTHER。 */
	@Test
	public void retryExhaustedOtherErrorType() {
		InMemoryMetricReader reader = InMemoryMetricReader.create();
		SdkMeterProvider mp = SdkMeterProvider.builder().registerMetricReader(reader).build();
		RetryListener l = new OtelRetryListener(mp, "openai", "chat");
		l.onRetryExhausted(3, -1, null, "/chat");

		Map<String, String> attrs = new HashMap<>();
		attrs.put("error.type", "_OTHER");
		Collection<MetricData> metrics = reader.collectAllMetrics();
		assertTrue(findLong(metrics, "sureai.client.retries", attrs) != null);
	}

	private static LongPointData findLong(Collection<MetricData> metrics, String name,
			Map<String, String> attrs) {
		for (MetricData md : metrics) {
			if (md.getName().equals(name)) {
				for (LongPointData p : md.getLongSumData().getPoints()) {
					boolean ok = true;
					for (Map.Entry<String, String> e : attrs.entrySet()) {
						if (!e.getValue().equals(p.getAttributes()
							.get(AttributeKey.stringKey(e.getKey())))) {
							ok = false;
						}
					}
					if (ok) {
						return p;
					}
				}
			}
		}
		return null;
	}

	private static boolean foundLongPoint(Collection<MetricData> metrics, String name,
			Map<String, String> attrs) {
		return findLong(metrics, name, attrs) != null;
	}
}
