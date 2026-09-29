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
package com.sure.ai.agent.approval;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sure.tool.lang.Assert;

/**
 * 带超时的审批处理器：包装另一个 {@link ApprovalHandler}，在指定时间内未返回决定时
 * 自动产生 {@link ApprovalStatus#TIMEOUT} 决定。
 *
 * <p>被包装 handler 在公共 ForkJoinPool 中异步执行；超时后不再等待其结果，
 * 后台任务会被中断性取消（best-effort，不保证立即停止阻塞中的 handler）。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class TimeoutApprovalHandler implements ApprovalHandler {

	/** 被包装的实际审批处理器。 */
	private final ApprovalHandler delegate;

	/** 等待决定的最大时长。 */
	private final Duration timeout;

	/**
	 * 构造。
	 *
	 * @param delegate 被包装的处理器（非 null）
	 * @param timeout   超时上限（正时长）
	 */
	public TimeoutApprovalHandler(ApprovalHandler delegate, Duration timeout) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(timeout, "timeout must not be null");
		Assert.isTrue(!timeout.isNegative() && !timeout.isZero(), "timeout must be positive");
		this.delegate = delegate;
		this.timeout = timeout;
	}

	@Override
	public ApprovalDecision request(ApprovalRequest request) {
		CompletableFuture<ApprovalDecision> future =
			CompletableFuture.supplyAsync(() -> this.delegate.request(request));
		try {
			return future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			return ApprovalDecision.timeout();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			return new ApprovalDecision(ApprovalStatus.TIMEOUT, "审批等待被中断",
				System.currentTimeMillis());
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new IllegalStateException("审批处理器执行失败: " + cause.getMessage(), cause);
		}
	}

	@Override
	public String name() {
		return "timeout(" + this.delegate.name() + ")";
	}
}
