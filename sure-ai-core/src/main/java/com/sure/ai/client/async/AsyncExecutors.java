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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * 异步 / 虚拟线程执行工具（1.9.0 批次 3）。
 *
 * <h2>执行模型</h2>
 * <p>统一以 <b>Java 21 虚拟线程</b>承载所有阻塞式 AI 请求 IO：共享一个
 * {@link Executors#newThreadPerTaskExecutor(ThreadFactory)} 虚拟线程工厂执行器，
 * 每个异步任务占用一条独立虚拟线程。虚拟线程轻量、可大规模挂载，且对阻塞 IO（HTTP 等待、
 * SSE 流式轮询、视频/图像任务轮询）天然友好——阻塞时自动让出载体平台线程。</p>
 *
 * <h2>共享单例与关闭语义</h2>
 * <p>{@link #virtualThreadExecutor()} 返回进程级共享的长生命周期执行器，命名前缀
 * {@code "sureai-vt-"}，虚拟线程均为守护线程（不阻止 JVM 退出）。<b>该执行器随进程存活，
 * 不随任何 {@code Async*Client} 实例关闭而 shutdown</b>——装饰器的 {@code close()} 仅委托底层
 * 业务客户端释放连接资源。需要受控生命周期的调用方应通过带 {@link Executor} 的构造器注入
 * 自建执行器，并自行管理其关闭。</p>
 *
 * <h2>异常与取消</h2>
 * <p>{@link #supplyAsync(Supplier, Executor)} / {@link #runAsync(Runnable, Executor)} 以裸
 * {@link CompletableFuture} 承载结果：任务抛出的异常<b>原样</b>进入异常完成态（调用方
 * {@code get()} 时包在 {@link java.util.concurrent.ExecutionException} 的 cause 中，
 * 不做二次包装）。{@code future.cancel(true)} 会在未来取消时<b>中断工作虚拟线程</b>
 * （虚拟线程可安全中断，阻塞 IO 会以 {@link InterruptedException} 解除阻塞）。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class AsyncExecutors {

	/** 进程级共享虚拟线程执行器：每任务一条虚拟线程，守护线程、带命名前缀便于排查。 */
	private static final ExecutorService VIRTUAL = Executors.newThreadPerTaskExecutor(
		Thread.ofVirtual().name("sureai-vt-", 0).factory());

	/** 工具类禁止实例化。 */
	private AsyncExecutors() {
	}

	/**
	 * 取共享的虚拟线程 per-task 执行器（单例，勿 shutdown）。
	 *
	 * @return 共享虚拟线程执行器
	 */
	public static ExecutorService virtualThreadExecutor() {
		return VIRTUAL;
	}

	/**
	 * 在给定执行器上异步执行有返回值任务，返回可取消的 {@link CompletableFuture}。
	 *
	 * <p>与 {@link CompletableFuture#supplyAsync(Supplier, Executor)} 的差异在于：本方法注册了
	 * 取消钩子——当返回的 future 被 {@code cancel(true)} 取消时，正在执行任务的工作线程会被
	 * {@linkplain Thread#interrupt() 中断}（虚拟线程上的阻塞 IO 据此解除阻塞）。任务正常完成或
	 * 抛出的异常均原样汇入 future，不做包装。</p>
	 *
	 * @param <T>      结果类型
	 * @param task     同步任务（通常为对底层 client 的一次阻塞调用）
	 * @param executor 执行器
	 * @return 可取消的未来结果；异常以原异常作为 cause
	 */
	public static <T> CompletableFuture<T> supplyAsync(Supplier<T> task, Executor executor) {
		Objects.requireNonNull(task, "task must not be null");
		Objects.requireNonNull(executor, "executor must not be null");
		CompletableFuture<T> future = new CompletableFuture<>();
		executor.execute(() -> {
			if (future.isCancelled()) {
				return;
			}
			Thread worker = Thread.currentThread();
			// 仅用于注册“取消即中断工作线程”的副作用钩子；结果丢弃。
			future.exceptionally(err -> {
				if (err instanceof CancellationException) {
					worker.interrupt();
				}
				return null;
			});
			try {
				T value = task.get();
				if (!future.isCancelled()) {
					future.complete(value);
				}
			} catch (Throwable ex) {
				future.completeExceptionally(ex);
			}
		});
		return future;
	}

	/**
	 * 在给定执行器上异步执行无返回值任务（如流式对话消费完毕）。
	 *
	 * <p>未来在任务正常返回（流结束）时以 {@code null} 完成，异常时以原异常完成；
	 * {@code cancel(true)} 语义同 {@link #supplyAsync(Supplier, Executor)}。</p>
	 *
	 * @param task     同步任务
	 * @param executor 执行器
	 * @return 可取消的未来（成功完成于流结束）
	 */
	public static CompletableFuture<Void> runAsync(Runnable task, Executor executor) {
		Objects.requireNonNull(task, "task must not be null");
		return supplyAsync(() -> {
			task.run();
			return null;
		}, executor);
	}
}
