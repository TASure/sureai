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

import com.sure.ai.client.observability.MetricsCollector;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;

/**
 * 把 sureai {@link MetricsCollector} 回调桥接为 OpenTelemetry GenAI 语义约定指标的采集器。
 *
 * <p>映射关系（指标名/单位逐字取自 OpenTelemetry GenAI 语义约定，见
 * <a href="https://github.com/open-telemetry/semantic-conventions-genai">semantic-conventions-genai</a>）：</p>
 * <ul>
 *   <li>{@code gen_ai.client.operation.duration}（DoubleHistogram，单位 {@code s}）——
 *       成功/失败终态各记录一次操作耗时（毫秒换算为秒）；失败时附带 {@code error.type}。</li>
 *   <li>{@code gen_ai.client.inference.usage.input_tokens}（LongCounter，单位 {@code {token}}）——
 *       {@code onTokenUsage} 的 prompt tokens，附 {@code gen_ai.token.modality=text} 与
 *       {@code gen_ai.request.model}。</li>
 *   <li>{@code gen_ai.client.inference.usage.output_tokens}（LongCounter，单位 {@code {token}}）——
 *       {@code onTokenUsage} 的 completion tokens。</li>
 * </ul>
 *
 * <p>公共属性：{@code gen_ai.operation.name}（默认 {@code chat}，可按 embeddings/text_completion 等配置）、
 * {@code gen_ai.provider.name}（如 {@code openai}/{@code anthropic}，由使用方配置；留空则不写该属性）。
 * {@code error.type} 取异常类简单名或 HTTP 状态码字符串（低基数）。</p>
 *
 * <p><b>无感降级</b>：传入 {@code null} MeterProvider（或 {@code MeterProvider.noop()}）时，
 * 所有方法为空操作、零开销；未在 runtime 引入 OpenTelemetry SDK 时，本类不会被加载，
 * 现有行为完全不变。SDK 与导出器（OTLP/Jaeger/Tempo/Langfuse）由使用方自行提供。</p>
 *
 * <p>接入：{@code AiConfig.builder().metricsCollector(OtelSupport.metricsCollector(meterProvider,"openai"))}</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class OtelGenAiMetrics implements MetricsCollector {

	/** 仪器作用域名（Instrumentation Scope）。 */
	static final String INSTRUMENTATION_SCOPE = "com.sure.ai.otel";

	/** GenAI 操作耗时直方图名。 */
	static final String METRIC_OPERATION_DURATION = "gen_ai.client.operation.duration";
	/** 输入 token 计数器名。 */
	static final String METRIC_INPUT_TOKENS = "gen_ai.client.inference.usage.input_tokens";
	/** 输出 token 计数器名。 */
	static final String METRIC_OUTPUT_TOKENS = "gen_ai.client.inference.usage.output_tokens";

	/** 操作名属性键。 */
	static final String ATTR_OPERATION_NAME = "gen_ai.operation.name";
	/** 提供方属性键。 */
	static final String ATTR_PROVIDER_NAME = "gen_ai.provider.name";
	/** 请求模型属性键。 */
	static final String ATTR_REQUEST_MODEL = "gen_ai.request.model";
	/** token 模态属性键。 */
	static final String ATTR_TOKEN_MODALITY = "gen_ai.token.modality";
	/** 错误类型属性键（稳定语义约定）。 */
	static final String ATTR_ERROR_TYPE = "error.type";

	/** 默认操作名：聊天补全。 */
	static final String DEFAULT_OPERATION = "chat";
	/** token 模态：文本（sureai 当前统一记 text）。 */
	static final String MODALITY_TEXT = "text";

	private final boolean noop;
	private final String operationName;
	private final String providerName;
	private final DoubleHistogram operationDuration;
	private final LongCounter inputTokens;
	private final LongCounter outputTokens;

	/**
	 * 以默认操作名 {@code chat} 构造。
	 *
	 * @param meterProvider OTel MeterProvider；为 {@code null} 时全空操作
	 */
	public OtelGenAiMetrics(MeterProvider meterProvider) {
		this(meterProvider, null, DEFAULT_OPERATION);
	}

	/**
	 * 全参构造。
	 *
	 * @param meterProvider OTel MeterProvider；为 {@code null} 时全空操作
	 * @param providerName  {@code gen_ai.provider.name} 取值（如 {@code openai}）；留空则不写该属性
	 * @param operationName {@code gen_ai.operation.name} 取值（如 {@code chat}/{@code embeddings}）；
	 *                      留空时回退为 {@code chat}
	 */
	public OtelGenAiMetrics(MeterProvider meterProvider, String providerName, String operationName) {
		this.noop = meterProvider == null;
		this.providerName = providerName;
		this.operationName = (operationName == null || operationName.isBlank()) ? DEFAULT_OPERATION
			: operationName;
		if (this.noop) {
			this.operationDuration = null;
			this.inputTokens = null;
			this.outputTokens = null;
			return;
		}
		Meter meter = meterProvider.get(INSTRUMENTATION_SCOPE);
		this.operationDuration = meter.histogramBuilder(METRIC_OPERATION_DURATION)
			.setUnit("s")
			.setDescription("GenAI operation duration.")
			.build();
		this.inputTokens = meter.counterBuilder(METRIC_INPUT_TOKENS)
			.setUnit("{token}")
			.setDescription("The number of input (prompt) tokens used, including cached tokens.")
			.build();
		this.outputTokens = meter.counterBuilder(METRIC_OUTPUT_TOKENS)
			.setUnit("{token}")
			.setDescription("The number of output (completion) tokens used, including reasoning tokens.")
			.build();
	}

	@Override
	public void onRequestStart(String path) {
		// 起始不产生终态测量，成功/失败终态已覆盖耗时
	}

	@Override
	public void onRequestSuccess(String path, int httpStatus, long durationMs) {
		if (this.noop) {
			return;
		}
		this.operationDuration.record(toSeconds(durationMs), baseAttributes().build());
	}

	@Override
	public void onRequestFailure(String path, int httpStatus, Exception exception, long durationMs) {
		if (this.noop) {
			return;
		}
		AttributesBuilder b = baseAttributes();
		putIfNotNull(b, ATTR_ERROR_TYPE, errorType(httpStatus, exception));
		this.operationDuration.record(toSeconds(durationMs), b.build());
	}

	@Override
	public void onRetry(String path, int attempt, int httpStatus) {
		// 重试由 OtelRetryListener（RetryListener SPI）负责，避免与失败终态重复计数
	}

	@Override
	public void onTokenUsage(String model, long promptTokens, long completionTokens, long totalTokens) {
		if (this.noop) {
			return;
		}
		AttributesBuilder b = baseAttributes();
		putIfNotNull(b, ATTR_TOKEN_MODALITY, MODALITY_TEXT);
		putIfNotNull(b, ATTR_REQUEST_MODEL, model);
		Attributes attrs = b.build();
		this.inputTokens.add(promptTokens, attrs);
		this.outputTokens.add(completionTokens, attrs);
	}

	/** 构建公共属性（operation.name + provider.name）。 */
	private AttributesBuilder baseAttributes() {
		AttributesBuilder b = Attributes.builder();
		b.put(ATTR_OPERATION_NAME, this.operationName);
		putIfNotNull(b, ATTR_PROVIDER_NAME, this.providerName);
		return b;
	}

	/** 仅当 value 非空时写入字符串属性。 */
	private static void putIfNotNull(AttributesBuilder b, String key, String value) {
		if (value != null && !value.isBlank()) {
			b.put(key, value);
		}
	}

	/** 毫秒转秒。 */
	private static double toSeconds(long durationMs) {
		return durationMs / 1000.0d;
	}

	/** 低基数 error.type：异常类简单名优先，否则 HTTP 状态码字符串。 */
	private static String errorType(int httpStatus, Exception exception) {
		if (exception != null) {
			return exception.getClass().getSimpleName();
		}
		if (httpStatus > 0) {
			return String.valueOf(httpStatus);
		}
		return "_OTHER";
	}
}
