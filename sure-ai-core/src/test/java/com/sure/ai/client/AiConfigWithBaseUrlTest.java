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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.client.cache.CacheStore;
import com.sure.ai.client.cache.LruCacheStore;
import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;
import com.sure.ai.client.resilience.CircuitBreaker;

/**
 * {@link AiConfig#withBaseUrl(String)} 回归测试：验证全部 14 个字段在 baseUrl 替换后原样保留，
 * 防止平台子类 Builder 重建丢失跨切面字段（metricsCollector / retryListeners /
 * rateLimitQps / cacheStore / cacheTtl / circuitBreaker）。
 *
 * @author sureai
 * @since 1.2.1
 */
public class AiConfigWithBaseUrlTest {

	/** 构造带全部字段的 AiConfig。 */
	private AiConfig buildFullConfig() {
		MetricsCollector metrics = new MetricsCollector() {
		};
		RetryListener listener = new RetryListener() {
		};
		CacheStore cacheStore = new LruCacheStore(16);
		CircuitBreaker cb = CircuitBreaker.builder()
			.failureThreshold(3)
			.windowSize(10)
			.openTimeoutMs(5000)
			.build();

		return AiConfig.builder()
			.apiKey("test-api-key")
			.baseUrl("https://old.example.com/v1")
			.timeout(Duration.ofSeconds(120))
			.connectTimeout(Duration.ofSeconds(15))
			.proxy("proxy.example.com:8080")
			.organization("org-123")
			.extraHeader("X-Custom", "custom-value")
			.maxRetries(5)
			.retryListener(listener)
			.metricsCollector(metrics)
			.rateLimitQps(10.0)
			.cacheStore(cacheStore)
			.cacheTtl(Duration.ofMinutes(30))
			.circuitBreaker(cb)
			.build();
	}

	@Test
	public void withBaseUrl_preservesAllFields() {
		AiConfig original = buildFullConfig();
		AiConfig replaced = original.withBaseUrl("https://new.example.com/v2");

		// baseUrl 变为新值
		assertEquals("https://new.example.com/v2", replaced.baseUrl());

		// 其余 13 个字段全部不变
		assertEquals("test-api-key", replaced.apiKey());
		assertEquals(Duration.ofSeconds(120), replaced.timeout());
		assertEquals(Duration.ofSeconds(15), replaced.connectTimeout());
		assertEquals("proxy.example.com:8080", replaced.proxy());
		assertEquals("org-123", replaced.organization());
		assertEquals(Map.of("X-Custom", "custom-value"), replaced.extraHeaders());
		assertEquals(5, replaced.maxRetries());
		assertEquals(10.0, replaced.rateLimitQps(), 0.0);
		assertEquals(Duration.ofMinutes(30), replaced.cacheTtl());

		// 引用类型字段必须是同一个实例（不可变对象直接引用）
		assertSame("retryListeners 应保持同一实例",
			original.retryListeners(), replaced.retryListeners());
		assertEquals(1, replaced.retryListeners().size());

		assertSame("metricsCollector 应保持同一实例",
			original.metricsCollector(), replaced.metricsCollector());
		assertSame("cacheStore 应保持同一实例",
			original.cacheStore(), replaced.cacheStore());
		assertSame("circuitBreaker 应保持同一实例",
			original.circuitBreaker(), replaced.circuitBreaker());
	}

	@Test
	public void withBaseUrl_returnsNewInstance() {
		AiConfig original = buildFullConfig();
		AiConfig replaced = original.withBaseUrl("https://new.example.com");

		assertNotSame("withBaseUrl 必须返回新实例", original, replaced);
		// 原实例 baseUrl 不变
		assertEquals("https://old.example.com/v1", original.baseUrl());
	}

	@Test
	public void withBaseUrl_originalConfigUnchanged() {
		AiConfig original = buildFullConfig();
		String originalBaseUrl = original.baseUrl();

		original.withBaseUrl("https://should-not-affect.example.com");

		assertEquals("原配置 baseUrl 不应被修改", originalBaseUrl, original.baseUrl());
		assertEquals("test-api-key", original.apiKey());
		assertEquals(5, original.maxRetries());
		assertEquals(10.0, original.rateLimitQps(), 0.0);
	}

	@Test
	public void withBaseUrl_nullOriginalBaseUrl() {
		// 构造 baseUrl 为 null 的配置（Builder 允许不设 baseUrl）
		AiConfig config = AiConfig.builder()
			.apiKey("key-no-base")
			.timeout(Duration.ofSeconds(30))
			.maxRetries(3)
			.build();

		AiConfig replaced = config.withBaseUrl("https://default.example.com");

		assertEquals("https://default.example.com", replaced.baseUrl());
		assertEquals("key-no-base", replaced.apiKey());
		assertEquals(Duration.ofSeconds(30), replaced.timeout());
		assertEquals(3, replaced.maxRetries());
	}

	@Test
	public void withBaseUrl_emptyOptionalFields() {
		// 只有必填 apiKey，其余可选字段全为默认/null
		AiConfig config = AiConfig.of("minimal-key");
		AiConfig replaced = config.withBaseUrl("https://minimal.example.com");

		assertEquals("minimal-key", replaced.apiKey());
		assertEquals("https://minimal.example.com", replaced.baseUrl());
		assertEquals(2, replaced.maxRetries()); // Builder 默认值
		assertEquals(0.0, replaced.rateLimitQps(), 0.0);
		assertTrue(replaced.extraHeaders().isEmpty());
		assertTrue(replaced.retryListeners().isEmpty());
		assertEquals(null, replaced.metricsCollector());
		assertEquals(null, replaced.cacheStore());
		assertEquals(null, replaced.cacheTtl());
		assertEquals(null, replaced.circuitBreaker());
		assertEquals(null, replaced.proxy());
		assertEquals(null, replaced.organization());
	}

	@Test
	public void withBaseUrl_retainsRetryListenerInstance() {
		RetryListener listener = new RetryListener() {
		};
		AiConfig config = AiConfig.builder()
			.apiKey("key")
			.retryListener(listener)
			.build();

		AiConfig replaced = config.withBaseUrl("https://retry.example.com");

		List<RetryListener> listeners = replaced.retryListeners();
		assertEquals(1, listeners.size());
		assertSame("retryListeners 列表必须是同一不可变实例",
			config.retryListeners(), listeners);
	}
}
