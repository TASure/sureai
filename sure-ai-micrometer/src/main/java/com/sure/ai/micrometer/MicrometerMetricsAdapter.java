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

package com.sure.ai.micrometer;

import java.util.concurrent.TimeUnit;

import com.sure.ai.client.observability.MetricsCollector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * 将 {@link MetricsCollector} 回调桥接到 Micrometer {@link MeterRegistry} 的适配器。
 *
 * <p>指标命名：</p>
 * <ul>
 *   <li>{@code sureai.requests.success(path,status)} 成功计数 + {@code sureai.request.duration} 计时</li>
 *   <li>{@code sureai.requests.failure(path,status)} 失败计数</li>
 *   <li>{@code sureai.requests.retry(path,status)} 重试计数</li>
 *   <li>{@code sureai.tokens.prompt / completion / total(path)} Token 累计</li>
 * </ul>
 *
 * <p>构造器注入 {@code MeterRegistry}（如 {@code SimpleMeterRegistry} / Prometheus 实现），
 * 再通过 {@code AiConfig.builder().metricsCollector(adapter).build()} 挂载。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public class MicrometerMetricsAdapter implements MetricsCollector {

	private final MeterRegistry registry;

	/**
	 * 构造适配器。
	 *
	 * @param registry Micrometer 注册表
	 */
	public MicrometerMetricsAdapter(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void onRequestStart(String path) {
		// 起始不单独埋点：成功/失败终态已覆盖请求数
	}

	@Override
	public void onRequestSuccess(String path, int httpStatus, long durationMs) {
		Counter.builder("sureai.requests.success")
			.tags(Tags.of("path", nullSafe(path), "status", String.valueOf(httpStatus)))
			.register(this.registry)
			.increment();
		Timer.builder("sureai.request.duration")
			.tags(Tags.of("path", nullSafe(path)))
			.register(this.registry)
			.record(durationMs, TimeUnit.MILLISECONDS);
	}

	@Override
	public void onRequestFailure(String path, int httpStatus, Exception exception,
			long durationMs) {
		Counter.builder("sureai.requests.failure")
			.tags(Tags.of("path", nullSafe(path), "status", String.valueOf(httpStatus)))
			.register(this.registry)
			.increment();
		Timer.builder("sureai.request.duration")
			.tags(Tags.of("path", nullSafe(path)))
			.register(this.registry)
			.record(durationMs, TimeUnit.MILLISECONDS);
	}

	@Override
	public void onRetry(String path, int attempt, int httpStatus) {
		Counter.builder("sureai.requests.retry")
			.tags(Tags.of("path", nullSafe(path), "status", String.valueOf(httpStatus)))
			.register(this.registry)
			.increment();
	}

	@Override
	public void onTokenUsage(String model, long promptTokens, long completionTokens,
			long totalTokens) {
		Counter.builder("sureai.tokens.prompt")
			.tags(Tags.of("model", nullSafe(model)))
			.register(this.registry)
			.increment(promptTokens);
		Counter.builder("sureai.tokens.completion")
			.tags(Tags.of("model", nullSafe(model)))
			.register(this.registry)
			.increment(completionTokens);
		Counter.builder("sureai.tokens.total")
			.tags(Tags.of("model", nullSafe(model)))
			.register(this.registry)
			.increment(totalTokens);
	}

	private static String nullSafe(String s) {
		return s == null ? "unknown" : s;
	}
}
