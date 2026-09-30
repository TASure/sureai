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

import com.sure.ai.agent.event.AgentEventSink;
import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;

import io.opentelemetry.api.metrics.MeterProvider;

/**
 * OpenTelemetry 桥接静态入口（对齐 sureai Util 门面范式）。
 *
 * <p>典型接入：</p>
 * <pre>{@code
 * // 1) 应用侧自行构建 OpenTelemetry SDK（runtime 引入 opentelemetry-sdk + 导出器）
 * OpenTelemetry otel = OpenTelemetrySdk.builder()...build();
 * MeterProvider mp = otel.getMeterProvider();
 *
 * // 2) 构造 sureai client 时挂载桥接
 * AiConfig config = AiConfig.builder()
 *     .apiKey(key)
 *     .metricsCollector(OtelSupport.metricsCollector(mp, "openai", "chat"))
 *     .retryListener(OtelSupport.retryListener(mp, "openai", "chat"))
 *     .build();
 *
 * // 3) Agent 事件桥接为 span 事件（订阅事件广播器）
 * publisher.subscribe(OtelSupport.agentEventSink());
 * }</pre>
 *
 * <p>约定：sure-ai-otel 只依赖 OpenTelemetry <b>API</b>（provided），SDK 与导出器由使用方在 runtime
 * 自行提供；传入 {@code null} MeterProvider 时返回空操作实现，现有行为完全不变。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class OtelSupport {

	private OtelSupport() {
	}

	/**
	 * 以默认操作名 {@code chat} 创建指标采集器桥接。
	 *
	 * @param meterProvider OTel MeterProvider；为 {@code null} 时返回空操作实现
	 * @return MetricsCollector 桥接实例
	 */
	public static MetricsCollector metricsCollector(MeterProvider meterProvider) {
		return new OtelGenAiMetrics(meterProvider);
	}

	/**
	 * 创建指标采集器桥接。
	 *
	 * @param meterProvider OTel MeterProvider；为 {@code null} 时返回空操作实现
	 * @param providerName  {@code gen_ai.provider.name} 取值（如 {@code openai}）；可空
	 * @param operationName {@code gen_ai.operation.name} 取值（如 {@code chat}/{@code embeddings}）；可空
	 * @return MetricsCollector 桥接实例
	 */
	public static MetricsCollector metricsCollector(MeterProvider meterProvider, String providerName,
			String operationName) {
		return new OtelGenAiMetrics(meterProvider, providerName, operationName);
	}

	/**
	 * 以默认操作名 {@code chat} 创建重试监听器桥接。
	 *
	 * @param meterProvider OTel MeterProvider；为 {@code null} 时返回空操作实现
	 * @return RetryListener 桥接实例
	 */
	public static RetryListener retryListener(MeterProvider meterProvider) {
		return new OtelRetryListener(meterProvider);
	}

	/**
	 * 创建重试监听器桥接。
	 *
	 * @param meterProvider OTel MeterProvider；为 {@code null} 时返回空操作实现
	 * @param providerName  {@code gen_ai.provider.name} 取值；可空
	 * @param operationName {@code gen_ai.operation.name} 取值；可空
	 * @return RetryListener 桥接实例
	 */
	public static RetryListener retryListener(MeterProvider meterProvider, String providerName,
			String operationName) {
		return new OtelRetryListener(meterProvider, providerName, operationName);
	}

	/**
	 * 创建 Agent 事件桥接订阅者（把 AgentEvent 写为当前 span 事件）。
	 *
	 * @return AgentEventSink 桥接实例
	 */
	public static AgentEventSink agentEventSink() {
		return new OtelAgentEventSink();
	}
}
