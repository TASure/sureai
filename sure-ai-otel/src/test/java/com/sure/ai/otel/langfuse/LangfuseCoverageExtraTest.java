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

package com.sure.ai.otel.langfuse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.sure.ai.client.observability.MetricsCollector;

/**
 * Langfuse 包内覆盖补齐：{@link LangfuseConfig} 的 null/blank 与 fromEnv、
 * {@link LangfuseBatchSender#noop()}、{@link LangfuseExporters} 门面、
 * {@link LangfuseIngestionClient} 的 null 配置/自定义 HttpClient/畸形 URI 分支、
 * {@link LangfuseMetricsCollector} 的公共构造器与发送异常兜底。
 *
 * <p>零真实网络。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class LangfuseCoverageExtraTest {

	private static final String PUB = "pk-lf-test";
	private static final String SEC = "sk-lf-test";

	/** of(null, null, null, null) → pk/sk 空串、禁用态。 */
	@Test
	public void ofNullKeysDisabled() {
		LangfuseConfig c = LangfuseConfig.of(null, null, null, null);
		assertFalse(c.isEnabled());
		assertEquals("", c.publicKey());
		assertEquals("", c.secretKey());
		assertNull(c.environment());
	}

	/** of(blank endpoint, ...) → 默认 host；environment 空白 → null。 */
	@Test
	public void ofBlankEndpointAndEnv() {
		LangfuseConfig c = LangfuseConfig.of("  ", PUB, SEC, "  ");
		assertEquals(LangfuseConfig.DEFAULT_HOST, c.endpoint());
		assertTrue(c.isEnabled());
		assertNull(c.environment());
		assertNotNull(c.basicAuthorization());
		assertTrue(c.basicAuthorization().startsWith("Basic "));
	}

	/** fromEnv 在无环境变量时返回禁用配置（覆盖 System.getenv 调用链）。 */
	@Test
	public void fromEnvWhenNoEnv() {
		LangfuseConfig c = LangfuseConfig.fromEnv();
		assertFalse(c.isEnabled());
		assertNotNull(c.ingestionUrl());
	}

	/** firstNonBlank 私有工具：第一非空优先、第二兜底、全空返回 null。 */
	@Test
	public void firstNonBlankBranches() throws Exception {
		Method m = LangfuseConfig.class.getDeclaredMethod("firstNonBlank", String.class, String.class);
		m.setAccessible(true);
		assertEquals("a", m.invoke(null, "  a  ", "b"));
		assertEquals("b", m.invoke(null, null, "  b  "));
		assertEquals("b", m.invoke(null, "   ", "b"));
		assertNull(m.invoke(null, null, null));
		assertNull(m.invoke(null, "  ", null));
	}

	/** noop 发送器：返回已完成 future，不抛异常。 */
	@Test
	public void noopSenderCompletes() {
		LangfuseBatchSender sender = LangfuseBatchSender.noop();
		CompletableFuture<Void> f = sender.send(List.of("e1"));
		f.join();
		assertTrue(f.isDone());
	}

	/** 门面 metricsCollectorFromEnv / metricsCollector 返回非空 MetricsCollector。 */
	@Test
	public void exportersFacadeReturnsCollector() {
		MetricsCollector c1 = LangfuseExporters.metricsCollectorFromEnv();
		assertNotNull(c1);
		MetricsCollector c2 = LangfuseExporters.metricsCollector(LangfuseConfig.disabled());
		assertNotNull(c2);
		MetricsCollector c3 = LangfuseExporters.metricsCollector(
			LangfuseConfig.of("http://localhost:1", PUB, SEC, null));
		assertNotNull(c3);
	}

	/** null 配置构造客户端：禁用态，send 不发请求。 */
	@Test
	public void nullConfigClientNoop() {
		LangfuseIngestionClient client = new LangfuseIngestionClient(null);
		client.send(List.of("e")).join();
	}

	/** 包私有构造器注入自定义 HttpClient：仅构造不发请求。 */
	@Test
	public void packagePrivateCtorStoresClient() {
		HttpClient http = HttpClient.newHttpClient();
		LangfuseIngestionClient client = new LangfuseIngestionClient(
			LangfuseConfig.disabled(), http);
		client.send(List.of("e")).join();
	}

	/** 畸形 ingestionUrl：构造 HttpRequest 抛异常被吞咽，future 正常完成。 */
	@Test
	public void malformedUriSwallowed() {
		LangfuseConfig cfg = LangfuseConfig.of("http://[invalid::", PUB, SEC, null);
		LangfuseIngestionClient client = new LangfuseIngestionClient(cfg);
		client.send(List.of("e")).join();
	}

	/** 公共构造器（启用配置）：仅构造不发请求，覆盖 new LangfuseIngestionClient 分支。 */
	@Test
	public void publicCtorEnabledConfig() {
		LangfuseMetricsCollector c = new LangfuseMetricsCollector(
			LangfuseConfig.of("http://localhost:1", PUB, SEC, "prod"));
		assertNotNull(c);
	}

	/** onRetry 无在途调用：空操作不抛异常。 */
	@Test
	public void retryWithoutStartNoop() {
		LangfuseMetricsCollector c = new LangfuseMetricsCollector(false,
			ev -> CompletableFuture.completedFuture(null), null);
		c.onRetry("/x", 1, 429);
	}

	/** finish 时 sender 抛异常：被吞咽并记 warning，不影响主流程。 */
	@Test
	public void finishSenderErrorSwallowed() {
		LangfuseBatchSender throwing = ev -> {
			throw new IllegalStateException("send-failed");
		};
		LangfuseMetricsCollector c = new LangfuseMetricsCollector(true, throwing, null);
		c.onRequestStart("/x");
		c.onRequestSuccess("/x", 200, 1);
	}

	/** errorMessage：异常无 message 时取类名；无异常无状态时取 request failed。 */
	@Test
	public void errorMessageBranches() {
		LangfuseMetricsCollector c = new LangfuseMetricsCollector(true,
			ev -> CompletableFuture.completedFuture(null), null);
		c.onRequestStart("/x");
		c.onRequestFailure("/x", -1, new NullPointerException(), 1);
		c.onRequestStart("/y");
		c.onRequestFailure("/y", -1, null, 1);
	}

	/** json 转义：\r 与其他控制字符（<0x20）。 */
	@Test
	public void jsonEscapeControlChars() {
		assertEquals("\"a\\rb\"", LangfuseMetricsCollector.json("a\rb"));
		assertEquals("\"a\\u0001b\"", LangfuseMetricsCollector.json("a\u0001b"));
	}
}
