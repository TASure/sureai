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

package com.sure.ai.client.observability;

/**
 * 指标采集 SPI。
 *
 * <p>{@code AbstractAiClient} 在请求生命周期的关键节点回调本接口；未挂载时（默认）
 * 仅一次 null 检查即返回，零额外开销。所有方法均为 {@code default} 空实现，
 * 按需覆写。生产环境建议接入 Micrometer/Prometheus 等后端（见
 * {@code sure-ai-micrometer} 模块或 docs/observability.md 的桥接示例）。</p>
 *
 * <p>回调在请求线程内同步执行，实现类应轻量、非阻塞且不得抛异常；
 * 抛出的异常会被 {@code AbstractAiClient} 捕获并仅记录 warning，不影响主流程。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public interface MetricsCollector {

	/**
	 * 逻辑请求开始（每次 doPost/doGet 等入口调用一次，不含重试）。
	 *
	 * @param path 请求路径
	 */
	default void onRequestStart(String path) {
	}

	/**
	 * 逻辑请求成功（2xx 终态）。
	 *
	 * @param path       请求路径
	 * @param httpStatus 成功的 HTTP 状态码
	 * @param durationMs  本次逻辑请求总耗时（含重试与退避）
	 */
	default void onRequestSuccess(String path, int httpStatus, long durationMs) {
	}

	/**
	 * 逻辑请求失败（非 2xx 耗尽 或 IO/中断异常）。
	 *
	 * @param path       请求路径
	 * @param httpStatus 最终失败的 HTTP 状态码（IO 异常时为 -1）
	 * @param exception  异常对象（HTTP 状态码触发时为 null）
	 * @param durationMs  本次逻辑请求总耗时（含重试与退避）
	 */
	default void onRequestFailure(String path, int httpStatus, Exception exception,
			long durationMs) {
	}

	/**
	 * 发生一次重试。
	 *
	 * @param path       请求路径
	 * @param attempt    第几次重试，从 1 开始
	 * @param httpStatus 触发重试的 HTTP 状态码
	 */
	default void onRetry(String path, int attempt, int httpStatus) {
	}

	/**
	 * 解析到一次 Token 用量（chat/embedding 响应）。
	 *
	 * @param model           模型名
	 * @param promptTokens    提示 token 数
	 * @param completionTokens 补全 token 数
	 * @param totalTokens     总 token 数
	 */
	default void onTokenUsage(String model, long promptTokens, long completionTokens,
			long totalTokens) {
	}
}
