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

import com.sure.ai.client.observability.MetricsCollector;

/**
 * Langfuse 原生导出静态门面（对齐 {@code OtelSupport} 门面范式）。
 *
 * <p>典型接入：</p>
 * <pre>{@code
 * // 方式一：读环境变量 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY（可选 LANGFUSE_HOST）
 * MetricsCollector langfuse = LangfuseExporters.metricsCollectorFromEnv();
 *
 * // 方式二：显式配置（自托管或指定环境标签）
 * MetricsCollector langfuse = LangfuseExporters.metricsCollector(
 *     LangfuseConfig.of("https://cloud.langfuse.com", "pk-lf-...", "sk-lf-...", "production"));
 *
 * AiConfig config = AiConfig.builder()
 *     .apiKey(key)
 *     .metricsCollector(langfuse)
 *     .build();
 * }</pre>
 *
 * <p>未配置密钥时返回空操作实现，现有行为完全不变。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
public final class LangfuseExporters {

	private LangfuseExporters() {
	}

	/**
	 * 从环境变量构建 Langfuse 指标导出器。
	 *
	 * @return MetricsCollector；未配齐密钥时为空操作
	 */
	public static MetricsCollector metricsCollectorFromEnv() {
		return metricsCollector(LangfuseConfig.fromEnv());
	}

	/**
	 * 以显式配置构建 Langfuse 指标导出器。
	 *
	 * @param config Langfuse 配置；为 {@code null} 或禁用态时返回空操作
	 * @return MetricsCollector
	 */
	public static MetricsCollector metricsCollector(LangfuseConfig config) {
		return new LangfuseMetricsCollector(config);
	}
}
