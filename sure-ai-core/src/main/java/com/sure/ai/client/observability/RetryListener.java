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
 * 重试事件回调监听器。
 *
 * <p>在 {@code AbstractAiClient} 统一重试模板的关键时机被回调，用于观测重试行为：
 * 每次进入退避等待前回调 {@link #onRetry}，重试耗尽即将抛错前回调
 * {@link #onRetryExhausted}。所有方法均为 {@code default} 空实现，按需覆写即可。</p>
 *
 * <p>回调在请求线程内同步执行；实现类务必轻量且不得抛异常——
 * 即使抛出异常也会被 {@code AbstractAiClient} 捕获并仅记录 warning，
 * 绝不影响主请求流程。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public interface RetryListener {

	/**
	 * 即将进行一次重试（退避等待前）回调。
	 *
	 * @param attempt    第几次重试，从 1 开始
	 * @param httpStatus 触发重试的 HTTP 状态码（IO 异常时为 -1）
	 * @param exception  触发重试的异常（HTTP 状态码触发时为 null，IO 异常时为异常对象）
	 * @param backoffMs  本次退避等待的毫秒数
	 * @param requestPath 请求路径（标识是哪个接口）
	 */
	default void onRetry(int attempt, int httpStatus, Exception exception, long backoffMs,
			String requestPath) {
	}

	/**
	 * 重试耗尽、即将抛出最终错误前回调。
	 *
	 * @param attempt    已执行的重试总次数
	 * @param httpStatus 最终失败的 HTTP 状态码（IO 异常时为 -1）
	 * @param exception  触发耗尽的异常（HTTP 状态码触发时为 null，IO 异常时为异常对象）
	 * @param requestPath 请求路径（标识是哪个接口）
	 */
	default void onRetryExhausted(int attempt, int httpStatus, Exception exception,
			String requestPath) {
	}
}
