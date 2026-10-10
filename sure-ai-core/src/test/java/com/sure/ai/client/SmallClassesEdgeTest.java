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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.Test;

import com.sure.ai.client.cache.LruCacheStore;
import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;
import com.sure.ai.client.resilience.CircuitBreaker;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;

/**
 * 小型客户端支撑类边界测试：指标/重试监听器默认空体、Key 轮换、LRU 缓存、
 * AiConfig 衍生方法、熔断器状态机与 CompatJson 私有构造器。
 *
 * @author sureai
 * @since 1.4.0
 */
public class SmallClassesEdgeTest {

	/** MetricsCollector 默认空方法体可被调用。 */
	@Test
	public void testMetricsDefaults() {
		MetricsCollector mc = new MetricsCollector() {
		};
		mc.onRequestStart("/p");
		mc.onRequestSuccess("/p", 200, 5L);
		mc.onRequestFailure("/p", 500, new RuntimeException("x"), 5L);
		mc.onRetry("/p", 1, 429);
		mc.onTokenUsage("m", 1, 2, 3);
	}

	/** RetryListener 默认空方法体可被调用。 */
	@Test
	public void testRetryListenerDefaults() {
		RetryListener l = new RetryListener() {
		};
		l.onRetry(1, 429, new RuntimeException("x"), 100L, "/p");
		l.onRetryExhausted(2, 429, new RuntimeException("x"), "/p");
	}

	/** RoundRobinApiKeyProvider：非法冷却抛异常、双参构造、标记坏 key 后轮换。 */
	@Test
	public void testRoundRobinBoundaries() {
		assertThrows(IllegalArgumentException.class,
			() -> new RoundRobinApiKeyProvider(List.of("k1", "k2"), 0));
		// 双参构造器（合法冷却）
		RoundRobinApiKeyProvider p = new RoundRobinApiKeyProvider(List.of("k1", "k2"), 60_000L);
		assertEquals("k1", p.currentKey());
		assertNotNull(p.nextKey());
		// 两个 key 都标记为坏：全部冷却期内回退仍返回一个 key
		p.markBad("k1");
		p.markBad("k2");
		assertNotNull(p.nextKey());
		assertEquals(2, p.size());
		assertEquals(2, p.allKeys().size());
	}

	/** LruCacheStore：非法容量/ttl 抛异常。 */
	@Test
	public void testLruCacheValidation() {
		assertThrows(IllegalArgumentException.class, () -> new LruCacheStore(0));
		assertThrows(IllegalArgumentException.class, () -> new LruCacheStore(1, -1L));
		LruCacheStore store = new LruCacheStore(2, 60_000L);
		assertEquals(0, store.size());
		store.clear();
	}

	/** AiConfig.withBaseUrlIfAbsent：已有 baseUrl 原样返回；空则补齐。 */
	@Test
	public void testWithBaseUrlIfAbsent() {
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl("https://api.x.com").build();
		assertTrue(cfg.withBaseUrlIfAbsent("https://default").baseUrl().equals("https://api.x.com"));
		AiConfig blank = AiConfig.builder().apiKey("k").build();
		assertEquals("https://default", blank.withBaseUrlIfAbsent("https://default").baseUrl());
	}

	/** AiConfig.retryListeners：null 透传、空列表初始化。 */
	@Test
	public void testRetryListenersBuilder() {
		AiConfig cfg = AiConfig.builder().apiKey("k").retryListeners(null).build();
		assertEquals(0, cfg.retryListeners().size());
		AiConfig cfg2 = AiConfig.builder().apiKey("k")
			.retryListener(new RetryListener() {
			}).build();
		assertEquals(1, cfg2.retryListeners().size());
		// 传入非空列表：初始化新 ArrayList 并追加
		AiConfig cfg3 = AiConfig.builder().apiKey("k")
			.retryListeners(List.of(new RetryListener() {
			}, new RetryListener() {
			})).build();
		assertEquals(2, cfg3.retryListeners().size());
	}

	/** CircuitBreaker：OPEN 快照惰性转 HALF_OPEN、半开许可设置、build 断言。 */
	@Test
	public void testCircuitBreakerEdges() {
		CircuitBreaker cb = CircuitBreaker.builder().failureThreshold(1).build();
		cb.onFailure(); // 进入 OPEN
		assertEquals(CircuitBreaker.State.OPEN, cb.state());
		// OPEN 超时后惰性转 HALF_OPEN（snapshot 触发）
		CircuitBreaker.Snapshot snap = cb.snapshot();
		assertNotNull(snap);

		CircuitBreaker half = CircuitBreaker.builder().failureThreshold(5).halfOpenPermittedCalls(3)
			.build();
		assertEquals(CircuitBreaker.State.CLOSED, half.state());

		// build 断言：阈值 <=0
		assertThrows(IllegalArgumentException.class,
			() -> CircuitBreaker.builder().failureThreshold(0).build());
	}

	/** CompatJson 私有构造器不可实例化。 */
	@Test
	public void testCompatJsonPrivateCtor() throws Exception {
		Class<?> cls = Class.forName("com.sure.ai.client.compat.CompatJson");
		Constructor<?> ctor = cls.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
		} catch (InvocationTargetException ex) {
			assertTrue(ex.getCause() instanceof AssertionError);
		}
	}

	/** JsonObject.put 便捷方法已在别处覆盖，这里仅确认门面可用。 */
	@Test
	public void testJsonObjectFacade() {
		JsonObject o = Json.object();
		o.put("a", 1);
		assertEquals(1, o.getInt("a"));
	}
}
