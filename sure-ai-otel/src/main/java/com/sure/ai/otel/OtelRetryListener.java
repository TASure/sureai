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

import com.sure.ai.client.observability.RetryListener;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;

/**
 * 把 sureai {@link RetryListener} 回调桥接为 OpenTelemetry 计数器的监听器。
 *
 * <p>说明：OpenTelemetry GenAI 语义约定目前（v1.44.0）尚未定义「重试」专用指标，
 * 为避免伪造官方 {@code gen_ai.*} 指标名，此处使用 sureai 自有命名空间
 * {@code sureai.client.retries} 作为扩展计数器，但其属性仍遵循 GenAI 语义约定：
 * {@code gen_ai.operation.name}、{@code gen_ai.provider.name}、{@code error.type}，
 * 并用扩展布尔属性 {@code sureai.retry.exhausted} 区分「进入退避重试」与「重试耗尽」。</p>
 *
 * <p><b>无感降级</b>：传入 {@code null} MeterProvider 时全空操作；未引入 OTel SDK 时本类不被加载。</p>
 *
 * <p>接入：{@code AiConfig.builder().retryListener(OtelSupport.retryListener(meterProvider,"openai"))}</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class OtelRetryListener implements RetryListener {

	/** 重试计数器名（sureai 扩展命名空间，GenAI 语义约定暂无对应官方指标）。 */
	static final String METRIC_RETRIES = "sureai.client.retries";
	/** 扩展属性：是否为重试耗尽终态。 */
	static final String ATTR_RETRY_EXHAUSTED = "sureai.retry.exhausted";

	private final boolean noop;
	private final String operationName;
	private final String providerName;
	private final LongCounter retries;

	/**
	 * 以默认操作名 {@code chat} 构造。
	 *
	 * @param meterProvider OTel MeterProvider；为 {@code null} 时全空操作
	 */
	public OtelRetryListener(MeterProvider meterProvider) {
		this(meterProvider, null, OtelGenAiMetrics.DEFAULT_OPERATION);
	}

	/**
	 * 全参构造。
	 *
	 * @param meterProvider OTel MeterProvider；为 {@code null} 时全空操作
	 * @param providerName  {@code gen_ai.provider.name} 取值；留空则不写该属性
	 * @param operationName {@code gen_ai.operation.name} 取值；留空回退为 {@code chat}
	 */
	public OtelRetryListener(MeterProvider meterProvider, String providerName, String operationName) {
		this.noop = meterProvider == null;
		this.providerName = providerName;
		this.operationName = (operationName == null || operationName.isBlank())
			? OtelGenAiMetrics.DEFAULT_OPERATION : operationName;
		if (this.noop) {
			this.retries = null;
			return;
		}
		Meter meter = meterProvider.get(OtelGenAiMetrics.INSTRUMENTATION_SCOPE);
		this.retries = meter.counterBuilder(METRIC_RETRIES)
			.setUnit("{event}")
			.setDescription("sureai client retry events (extension; GenAI semconv has no retry metric).")
			.build();
	}

	@Override
	public void onRetry(int attempt, int httpStatus, Exception exception, long backoffMs,
			String requestPath) {
		if (this.noop) {
			return;
		}
		this.retries.add(1, buildAttributes(httpStatus, exception, false));
	}

	@Override
	public void onRetryExhausted(int attempt, int httpStatus, Exception exception, String requestPath) {
		if (this.noop) {
			return;
		}
		this.retries.add(1, buildAttributes(httpStatus, exception, true));
	}

	/** 构建重试事件属性。 */
	private Attributes buildAttributes(int httpStatus, Exception exception, boolean exhausted) {
		AttributesBuilder b = Attributes.builder();
		b.put(OtelGenAiMetrics.ATTR_OPERATION_NAME, this.operationName);
		if (this.providerName != null && !this.providerName.isBlank()) {
			b.put(OtelGenAiMetrics.ATTR_PROVIDER_NAME, this.providerName);
		}
		b.put(OtelGenAiMetrics.ATTR_ERROR_TYPE, errorType(httpStatus, exception));
		b.put(ATTR_RETRY_EXHAUSTED, exhausted);
		return b.build();
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
