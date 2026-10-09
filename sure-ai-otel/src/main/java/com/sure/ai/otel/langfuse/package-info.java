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
 * Langfuse 原生 Ingestion 导出子包（零第三方依赖）。
 *
 * <p>把 sureai {@link com.sure.ai.client.observability.MetricsCollector} 的一次逻辑 LLM 调用
 * 映射为 Langfuse 的 {@code trace} + {@code generation} 观察，用 JDK 自带
 * {@code java.net.http.HttpClient} 以 JSON POST 到官方 {@code /api/public/ingestion} 端点
 * （Basic Auth = {@code base64(publicKey:secretKey)}）：</p>
 * <ul>
 *   <li>{@link com.sure.ai.otel.langfuse.LangfuseConfig} —— 端点/密钥/环境配置，环境变量读取与降级；</li>
 *   <li>{@link com.sure.ai.otel.langfuse.LangfuseIngestionClient} —— JDK HTTP 批量 POST 客户端；</li>
 *   <li>{@link com.sure.ai.otel.langfuse.LangfuseMetricsCollector} —— 生命周期 → trace/generation 事件映射；</li>
 *   <li>{@link com.sure.ai.otel.langfuse.LangfuseExporters} —— 静态门面入口。</li>
 * </ul>
 *
 * <p>本子包<b>不引用任何 OpenTelemetry 类</b>：即使 runtime 未引入 OTel SDK，也可独立加载使用。
 * 未配置密钥时全链路空操作；发送失败仅记录 warning、不破坏主流程。</p>
 */
package com.sure.ai.otel.langfuse;
