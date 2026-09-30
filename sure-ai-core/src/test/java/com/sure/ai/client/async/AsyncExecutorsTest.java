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

package com.sure.ai.client.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link AsyncExecutors} 单元测试（1.9.0）：虚拟线程执行器可用性、结果/异常传播、取消即中断。
 *
 * <p>零真实网络，全部在 JVM 内联任务上断言。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class AsyncExecutorsTest {

	/** 共享执行器返回非空且为同一实例。 */
	@Test
	public void virtualThreadExecutorIsSharedSingleton() {
		assertNotNull(AsyncExecutors.virtualThreadExecutor());
		assertSame(AsyncExecutors.virtualThreadExecutor(), AsyncExecutors.virtualThreadExecutor());
	}

	/** 默认执行器上执行的任务运行在虚拟线程上。 */
	@Test
	public void taskRunsOnVirtualThread() throws Exception {
		AtomicReference<Boolean> isVirtual = new AtomicReference<>(Boolean.FALSE);
		AtomicReference<String> name = new AtomicReference<>("");
		CompletableFuture<String> f = AsyncExecutors.supplyAsync(() -> {
			isVirtual.set(Thread.currentThread().isVirtual());
			name.set(Thread.currentThread().getName());
			return "done";
		}, AsyncExecutors.virtualThreadExecutor());
		assertEquals("done", f.get(5, TimeUnit.SECONDS));
		assertTrue("任务应运行在虚拟线程上", isVirtual.get());
		assertTrue("虚拟线程名应带 sureai-vt- 前缀", name.get().startsWith("sureai-vt-"));
	}

	/** 正常结果原样进入 future。 */
	@Test
	public void supplyAsyncPropagatesResult() throws Exception {
		CompletableFuture<Integer> f = AsyncExecutors.supplyAsync(() -> 42,
			AsyncExecutors.virtualThreadExecutor());
		assertEquals(Integer.valueOf(42), f.get(5, TimeUnit.SECONDS));
	}

	/** 任务抛出的异常不包装：ExecutionException 的 cause 即原异常。 */
	@Test
	public void supplyAsyncPropagatesExceptionUnwrapped() {
		AiException boom = new AiException("boom");
		CompletableFuture<Object> f = AsyncExecutors.supplyAsync(() -> {
			throw boom;
		}, AsyncExecutors.virtualThreadExecutor());
		ExecutionException ee = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
		assertSame(boom, ee.getCause());
	}

	/** runAsync 在任务正常返回后以 null 完成。 */
	@Test
	public void runAsyncCompletesNullOnSuccess() throws Exception {
		AtomicReference<Boolean> ran = new AtomicReference<>(Boolean.FALSE);
		CompletableFuture<Void> f = AsyncExecutors.runAsync(() -> ran.set(Boolean.TRUE),
			AsyncExecutors.virtualThreadExecutor());
		assertNull(f.get(5, TimeUnit.SECONDS));
		assertTrue(ran.get());
	}

	/** cancel(true) 未来后：future 标记取消，且工作线程被中断、未正常跑完。 */
	@Test
	public void cancelInterruptsWorker() throws Exception {
		AtomicReference<Boolean> interrupted = new AtomicReference<>(Boolean.FALSE);
		AtomicReference<Boolean> completedNormally = new AtomicReference<>(Boolean.FALSE);
		CompletableFuture<String> f = AsyncExecutors.supplyAsync(() -> {
			try {
				Thread.sleep(30_000);
			} catch (InterruptedException e) {
				interrupted.set(Boolean.TRUE);
				Thread.currentThread().interrupt();
				throw new AiException("interrupted");
			}
			completedNormally.set(Boolean.TRUE);
			return "should-not-reach";
		}, AsyncExecutors.virtualThreadExecutor());

		// 稍候让任务真正跑起来再取消
		Thread.sleep(200);
		assertTrue(f.cancel(true));
		assertTrue(f.isCancelled());

		long deadline = System.currentTimeMillis() + 5_000;
		while (!interrupted.get() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertTrue("工作虚拟线程应被取消中断", interrupted.get());
		assertFalse("任务不应正常跑完", completedNormally.get());
	}
}
