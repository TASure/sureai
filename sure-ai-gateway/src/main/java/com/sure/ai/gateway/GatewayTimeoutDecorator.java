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

package com.sure.ai.gateway;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * 请求级超时装饰器：把被装饰 {@link AiClient} 的每次调用放到专用单线程 daemon 执行器上执行，
 * 调用方通过 {@code future.get(timeout, MILLISECONDS)} 限时等待，超时即中断 worker 并抛出
 * {@link AiTimeoutException}。
 *
 * <h2>故障转移联动</h2>
 * <p>超时统一抛 {@link AiTimeoutException}，{@code FailoverConfig} 默认将其视为<b>可转移异常</b>，
 * 因此包在 {@link GatewayClient} 故障转移链外时，一次超时会自动触发切换到下一个候选客户端，
 * 对上层调用方透明。本类不修改 {@code GatewayClient}，注册时把装饰器包在真实 client 外即可：</p>
 *
 * <pre>
 * registry.register("openai", new GatewayTimeoutDecorator(openAiClient, 30_000));
 * </pre>
 *
 * <h2>per-request 覆盖</h2>
 * <p>请求 {@link ChatRequest#extra()} 中存在键 {@value #EXTRA_TIMEOUT_MS} 且其值为<b>正的
 * Number</b> 时，以该值（毫秒）作为本次调用超时；否则回退到构造器传入的默认超时。非法值
 * （null / 非 Number / 非正数）一律回退，不抛异常。</p>
 *
 * <h2>异常语义</h2>
 * <ul>
 *   <li>超时：{@code future.get} 抛 {@link TimeoutException} → {@code cancel(true)} 打断
 *       <b>本装饰器自己的 worker 线程</b>（不触碰调用方线程）→ 抛 {@link AiTimeoutException}，
 *       message 含超时毫秒数与请求模型名。</li>
 *   <li>调用方线程被中断：{@code future.get} 抛 {@link InterruptedException} → 恢复当前线程
 *       interrupt 标志 → 抛 {@code AiException("gateway timeout interrupted")}。</li>
 *   <li>被装饰 delegate 抛出业务异常：{@link ExecutionException} 解包 cause，
 *       <b>原样抛出（保持异常类型与实例）</b>，以便 {@code FailoverConfig} 按类型判定是否可转移。</li>
 * </ul>
 *
 * <h2>流式超时语义</h2>
 * <p>整个流式调用（含全部分片投递）超过 timeout 即抛 {@link AiTimeoutException}。实现上对
 * consumer 做包装转发：分片正常透传；一旦整体超时，用一个 volatile 标志让包装 consumer
 * <b>丢弃后续分片</b>（worker 线程可能仍在投递迟到的分片），再向调用方抛出超时异常。</p>
 *
 * <p>执行器懒创建（首次调用 chat/chatStream 时），单线程 daemon，线程名 {@code gateway-timeout}；
 * {@link #close()} 幂等：先 close delegate，再 {@code shutdownNow()} 执行器。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public final class GatewayTimeoutDecorator implements AiClient {

	/**
	 * per-request 超时覆盖键：放入 {@link ChatRequest#extra()}，值为正 Number（毫秒）时本次
	 * 调用以它为准；缺失或非法时回退构造器默认值。
	 */
	public static final String EXTRA_TIMEOUT_MS = "timeoutMs";

	/** 被装饰的真实客户端。 */
	private final AiClient delegate;

	/** 构造器默认超时（毫秒），恒为正数。 */
	private final long timeoutMs;

	/** 懒创建的单线程 daemon 执行器；close 后保留引用使其拒绝后续提交。 */
	private ExecutorService executor;

	/** 是否已 close（配合同步块阻止 close 后重建执行器）。 */
	private boolean closed;

	/**
	 * 构造超时装饰器。
	 *
	 * @param delegate  被装饰客户端（非空）
	 * @param timeoutMs 默认超时毫秒数（必须 &gt; 0）
	 * @throws NullPointerException     delegate 为 null
	 * @throws IllegalArgumentException timeoutMs 非正数
	 */
	public GatewayTimeoutDecorator(AiClient delegate, long timeoutMs) {
		Objects.requireNonNull(delegate, "delegate must not be null");
		if (timeoutMs <= 0) {
			throw new IllegalArgumentException("timeoutMs must be positive, got " + timeoutMs);
		}
		this.delegate = delegate;
		this.timeoutMs = timeoutMs;
	}

	@Override
	public String name() {
		return "timeout:" + this.delegate.name();
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		long effective = resolveTimeout(request);
		Future<ChatResponse> future = executor().submit((Callable<ChatResponse>) () -> this.delegate.chat(request));
		return await(future, effective, request, null);
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		long effective = resolveTimeout(request);
		DropFlag drop = new DropFlag();
		Consumer<ChatStreamChunk> wrapped = chunk -> {
			if (!drop.dropped) {
				consumer.accept(chunk);
			}
		};
		Future<Void> future = executor().submit(() -> {
			this.delegate.chatStream(request, wrapped);
			return null;
		});
		await(future, effective, request, () -> drop.dropped = true);
	}

	@Override
	public void close() {
		synchronized (this) {
			this.closed = true;
			this.delegate.close();
			if (this.executor != null) {
				this.executor.shutdownNow();
			}
		}
	}

	/**
	 * 解析本次调用的超时毫秒数：extra 中 {@value #EXTRA_TIMEOUT_MS} 为正 Number 时用它，
	 * 否则回退构造器默认值。
	 *
	 * @param request 请求
	 * @return 超时毫秒数
	 */
	private long resolveTimeout(ChatRequest request) {
		Object v = request.extra().get(EXTRA_TIMEOUT_MS);
		if (v instanceof Number n && n.longValue() > 0) {
			return n.longValue();
		}
		return this.timeoutMs;
	}

	/**
	 * 限时等待 future 完成，统一处理超时 / 中断 / delegate 异常。
	 *
	 * @param future    执行 future
	 * @param timeoutMs  超时毫秒数
	 * @param request    当前请求（用于 message 携带模型名）
	 * @param onTimeout 超时回调（流式场景用于置位丢弃标志），可为 null
	 * @param <T>        future 结果类型
	 * @return future 结果
	 */
	private <T> T await(Future<T> future, long timeoutMs, ChatRequest request, Runnable onTimeout) {
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			if (onTimeout != null) {
				onTimeout.run();
			}
			future.cancel(true);
			throw new AiTimeoutException("gateway timeout after " + timeoutMs
					+ " ms (model=" + request.model() + ")");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("gateway timeout interrupted", ex);
		} catch (ExecutionException ex) {
			throw propagate(ex.getCause());
		}
	}

	/**
	 * 解包 delegate 异常并原样抛出：RuntimeException / Error 保持类型与实例；
	 * 其余罕见受检异常包成 {@link AiException}。
	 *
	 * @param cause delegate 抛出的原始异常
	 * @return 待抛出的运行时异常
	 */
	private static RuntimeException propagate(Throwable cause) {
		if (cause instanceof RuntimeException re) {
			return re;
		}
		if (cause instanceof Error err) {
			throw err;
		}
		return new AiException("gateway delegate failure", cause);
	}

	/**
	 * 懒创建（首次调用时）单线程 daemon 执行器；close 后不再重建，直接抛 closed。
	 *
	 * @return 执行器
	 */
	private synchronized ExecutorService executor() {
		if (this.executor == null) {
			if (this.closed) {
				throw new AiException("gateway timeout decorator is closed");
			}
			ThreadFactory factory = r -> {
				Thread t = new Thread(r, "gateway-timeout");
				t.setDaemon(true);
				return t;
			};
			this.executor = Executors.newSingleThreadExecutor(factory);
		}
		return this.executor;
	}

	/** 流式超时后置位的分片丢弃标志（volatile：worker 线程写 / 包装 consumer 读）。 */
	private static final class DropFlag {

		/** true 表示整体已超时，后续分片丢弃。 */
		volatile boolean dropped;
	}
}
