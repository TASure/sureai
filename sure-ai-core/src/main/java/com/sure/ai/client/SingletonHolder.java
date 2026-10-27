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

package com.sure.ai.client;

import java.util.function.Supplier;

/**
 * 线程安全的懒加载单例容器，封装双检锁（double-checked locking, DCL）模式。
 *
 * <p>各平台 {@code XxxUtil} 历史上各自重复一份 {@code volatile} 字段 + 专用锁对象 +
 * DCL 判空 + 同步替换/置空的样板代码，本类将其收敛为一处通用实现。仅依赖 JDK
 * （{@code volatile} + {@code synchronized} + {@link Supplier}），不引入新依赖。</p>
 *
 * <p>典型用法：</p>
 * <pre>
 *   private static final SingletonHolder&lt;OpenAiClient&gt; HOLDER =
 *       new SingletonHolder&lt;&gt;(OpenAiUtil::buildClientFromEnv);
 *
 *   public static void init(AiConfig config) {
 *       HOLDER.set(new OpenAiClient(config));
 *   }
 *   public static OpenAiClient client() {
 *       return HOLDER.get();
 *   }
 *   public static void resetClient() {
 *       HOLDER.reset();
 *   }
 * </pre>
 *
 * <p>语义说明：</p>
 * <ul>
 *   <li>{@link #get()}：DCL 懒加载，实例为空时在锁内调用 {@code defaultSupplier} 构造；
 *       supplier 抛异常时实例保持为空，异常原样上抛。</li>
 *   <li>{@link #set(Object)}：在锁内无条件替换实例（用于显式 {@code init}）。</li>
 *   <li>{@link #getOrCreate(Supplier)}：与 {@link #get()} 相同的 DCL，但 supplier 由调用方
 *       当次传入（用于构造参数依赖调用参数的场景，如 Realtime 客户端）。</li>
 *   <li>{@link #reset()}：在锁内置空，下次 {@link #get()} 重新懒加载。</li>
 * </ul>
 *
 * @param <T> 单例类型
 * @author sureai
 * @since 1.4.0
 */
public final class SingletonHolder<T> {

	/** 单例实例，volatile 保证 DCL 正确性与可见性。 */
	private volatile T instance;

	/** 内部专用锁对象（不暴露给外部，避免 class 锁被外部代码参与）。 */
	private final Object lock = new Object();

	/** 懒加载默认工厂，可为 {@code null}（此时只能先 {@link #set} 或用 {@link #getOrCreate}）。 */
	private final Supplier<T> defaultSupplier;

	/**
	 * 创建单例容器。
	 *
	 * @param defaultSupplier 懒加载工厂，实例为空时在锁内调用；可为 {@code null}
	 *                        （随后须先 {@link #set} 或用 {@link #getOrCreate}）
	 */
	public SingletonHolder(Supplier<T> defaultSupplier) {
		this.defaultSupplier = defaultSupplier;
	}

	/**
	 * 获取单例，未初始化时用 {@code defaultSupplier} 懒加载。
	 *
	 * @return 单例实例（永不返回 {@code null}，除非 supplier 返回 {@code null}）
	 * @throws IllegalStateException 未设置 {@code defaultSupplier} 且尚未显式初始化
	 */
	public T get() {
		T current = instance;
		if (current == null) {
			synchronized (lock) {
				current = instance;
				if (current == null) {
					if (defaultSupplier == null) {
						throw new IllegalStateException("SingletonHolder 未配置默认 supplier，"
							+ "且尚未通过 set()/getOrCreate() 初始化，无法懒加载");
					}
					current = defaultSupplier.get();
					instance = current;
				}
			}
		}
		return current;
	}

	/**
	 * 获取单例，未初始化时用当次传入的工厂懒加载（DCL）。
	 *
	 * <p>实例已存在时直接返回既有实例，忽略本次传入的 supplier——与历史
	 * "首次构造后后续调用返回同一实例" 的语义一致。</p>
	 *
	 * @param supplier 当次懒加载工厂（仅在实例为空时于锁内调用一次）
	 * @return 单例实例
	 */
	public T getOrCreate(Supplier<T> supplier) {
		T current = instance;
		if (current == null) {
			synchronized (lock) {
				current = instance;
				if (current == null) {
					current = supplier.get();
					instance = current;
				}
			}
		}
		return current;
	}

	/**
	 * 在锁内无条件替换单例实例。
	 *
	 * @param value 新实例（通常为新构造的客户端）
	 */
	public void set(T value) {
		synchronized (lock) {
			instance = value;
		}
	}

	/** 在锁内置空单例实例，下次 {@link #get()} 重新懒加载。 */
	public void reset() {
		synchronized (lock) {
			instance = null;
		}
	}

	/**
	 * 是否已初始化（已持有实例）。
	 *
	 * @return 已持有实例返回 {@code true}
	 */
	public boolean isInitialized() {
		return instance != null;
	}
}
