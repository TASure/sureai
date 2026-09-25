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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * {@link SingletonHolder} 单元测试。
 *
 * @author sureai
 * @since 1.4.0
 */
public class SingletonHolderTest {

	/** 懒加载：get() 之前不调用 supplier。 */
	@Test
	public void testLazyInitialization() {
		AtomicInteger calls = new AtomicInteger();
		SingletonHolder<String> holder = new SingletonHolder<>(() -> {
			calls.incrementAndGet();
			return "v1";
		});
		assertFalse(holder.isInitialized());
		assertEquals(0, calls.get());

		String v = holder.get();
		assertEquals("v1", v);
		assertEquals(1, calls.get());

		// 再次 get() 不重复调用 supplier
		assertSame(v, holder.get());
		assertEquals(1, calls.get());
		assertTrue(holder.isInitialized());
	}

	/** 并发 get() 返回同一实例，supplier 仅被调用一次（DCL 线程安全）。 */
	@Test
	public void testDclThreadSafety() throws InterruptedException {
		final int threads = 32;
		AtomicInteger calls = new AtomicInteger();
		SingletonHolder<Object> holder = new SingletonHolder<>(() -> {
			calls.incrementAndGet();
			return new Object();
		});

		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		Object[] results = new Object[threads];

		for (int i = 0; i < threads; i++) {
			final int idx = i;
			Thread t = new Thread(() -> {
				ready.countDown();
				try {
					start.await();
					results[idx] = holder.get();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
			t.start();
		}

		ready.await();
		start.countDown();
		done.await();

		Object first = results[0];
		assertNotNull(first);
		for (int i = 0; i < threads; i++) {
			assertSame(first, results[i]);
		}
		assertEquals(1, calls.get());
	}

	/** set() 后 get() 返回新实例。 */
	@Test
	public void testSetReplacesInstance() {
		SingletonHolder<String> holder = new SingletonHolder<>(() -> "lazy");
		assertEquals("lazy", holder.get());
		Object first = holder.get();

		holder.set("manual");
		assertEquals("manual", holder.get());
		assertNotSame(first, holder.get());
	}

	/** reset() 后 get() 重新调用 supplier 构造新实例。 */
	@Test
	public void testResetClearsInstance() {
		AtomicInteger calls = new AtomicInteger();
		SingletonHolder<String> holder = new SingletonHolder<>(() -> "v" + calls.incrementAndGet());

		String v1 = holder.get();
		assertEquals("v1", v1);
		assertTrue(holder.isInitialized());

		holder.reset();
		assertFalse(holder.isInitialized());

		String v2 = holder.get();
		assertEquals("v2", v2);
		assertNotSame(v1, v2);
		assertEquals(2, calls.get());
	}

	/** supplier 为 null 且未 set 时 get() 抛清晰异常。 */
	@Test
	public void testNullSupplierThrows() {
		SingletonHolder<String> holder = new SingletonHolder<>(null);
		assertFalse(holder.isInitialized());
		IllegalStateException e = assertThrows(IllegalStateException.class, holder::get);
		assertTrue(e.getMessage() != null && !e.getMessage().isBlank());

		// set 之后可正常返回
		holder.set("after-set");
		assertEquals("after-set", holder.get());
	}

	/** isInitialized() 状态随 set/get/reset 流转。 */
	@Test
	public void testIsInitialized() {
		SingletonHolder<String> holder = new SingletonHolder<>(() -> "x");
		assertFalse(holder.isInitialized());
		holder.set("s");
		assertTrue(holder.isInitialized());
		holder.reset();
		assertFalse(holder.isInitialized());
	}

	/** getOrCreate：已初始化时返回既有实例并忽略本次 supplier；未初始化时用当次 supplier。 */
	@Test
	public void testGetOrCreateUsesSupplierOnlyOnFirstInit() {
		AtomicInteger calls = new AtomicInteger();
		SingletonHolder<String> holder = new SingletonHolder<>(null);

		String first = holder.getOrCreate(() -> "first-" + calls.incrementAndGet());
		assertEquals("first-1", first);
		// 第二次调用传入不同 supplier，但应返回既有实例，supplier 不再执行
		String second = holder.getOrCreate(() -> "second-" + calls.incrementAndGet());
		assertSame(first, second);
		assertEquals(1, calls.get());
	}

	/** supplier 抛异常时实例保持未初始化，下次 get() 可重试。 */
	@Test
	public void testSupplierExceptionLeavesInstanceUnset() {
		AtomicInteger calls = new AtomicInteger();
		SingletonHolder<String> holder = new SingletonHolder<>(() -> {
			if (calls.incrementAndGet() == 1) {
				throw new IllegalStateException("boom");
			}
			return "recovered";
		});

		assertThrows(IllegalStateException.class, holder::get);
		assertFalse(holder.isInitialized());

		assertEquals("recovered", holder.get());
		assertTrue(holder.isInitialized());
	}
}
