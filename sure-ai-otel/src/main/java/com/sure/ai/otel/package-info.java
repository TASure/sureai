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

/**
 * OpenTelemetry 可观测性桥接子包。
 *
 * <p>把 sureai 的 {@code MetricsCollector} / {@code RetryListener} / {@code AgentEventSink}
 * 回调映射为 OpenTelemetry GenAI 语义约定（{@code gen_ai.*}）指标与 span 事件：</p>
 * <ul>
 *   <li>{@link com.sure.ai.otel.OtelGenAiMetrics} —— 指标桥接（操作耗时直方图 + input/output token 计数器）；</li>
 *   <li>{@link com.sure.ai.otel.OtelRetryListener} —— 重试事件计数；</li>
 *   <li>{@link com.sure.ai.otel.OtelAgentEventSink} —— Agent 事件写为当前 span 事件；</li>
 *   <li>{@link com.sure.ai.otel.OtelSupport} —— 静态门面入口。</li>
 * </ul>
 *
 * <p>OpenTelemetry API 以 {@code provided} 引入、不传递给下游；runtime 的 SDK 与导出器由使用方提供。
 * 未配置 OTel（null MeterProvider / 无录制 span）时全链路空操作，零副作用。本模块不进入
 * {@code sure-ai-all} 聚合。</p>
 */
package com.sure.ai.otel;
