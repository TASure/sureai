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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.sure.ai.cost.CostAggregator;
import com.sure.ai.cost.CostCalculator;
import com.sure.ai.cost.PriceCatalog;
import com.sure.ai.exception.AiBudgetExceededException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * {@link GatewayUtil} 静态入口装配测试（零网络）：单例语义 / Builder 默认与自定义装配 /
 * 租户层 / 健康监视器。
 *
 * @author sureai
 * @since 2.7.0
 */
public class GatewayUtilTest {

	/** 每个用例后重置单例，避免污染其他测试。 */
	@After
	public void tearDown() {
		GatewayUtil.reset();
	}

	private static ChatRequest req() {
		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
	}

	/** getInstance 单例；reset 后换新实例。 */
	@Test
	public void singletonAndReset() {
		GatewayUtil u1 = GatewayUtil.getInstance();
		assertSame(u1, GatewayUtil.getInstance());
		GatewayUtil.reset();
		assertNotSame(u1, GatewayUtil.getInstance());
	}

	/** init 显式替换单例。 */
	@Test
	public void initReplacesSingleton() {
		GatewayUtil custom = GatewayUtil.builder().registry(new ClientRegistry()).build();
		GatewayUtil.init(custom);
		assertSame(custom, GatewayUtil.getInstance());
	}

	/** Builder 默认装配：注册后 gateway().chat 直通。 */
	@Test
	public void defaultBuilderCanChatAfterRegister() {
		ClientRegistry reg = new ClientRegistry();
		reg.register("openai", new FakeClient("x"));
		GatewayUtil util = GatewayUtil.builder().registry(reg).build();
		ChatResponse resp = util.gateway().chat(req());
		assertEquals("x:ok", resp.firstText());
		assertNotNull(util.metrics());
		assertNotNull(util.registry());
	}

	/** 自定义熔断配置 / 指标注入生效。 */
	@Test
	public void customBreakerConfigAndMetricsInjected() {
		ClientRegistry reg = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B");
		reg.register("a", a);
		reg.register("b", b);
		GatewayMetrics metrics = new GatewayMetrics();
		GatewayUtil util = GatewayUtil.builder()
			.registry(reg)
			.circuitBreakerConfig(CircuitBreakerConfig.builder()
				.failureThreshold(1).windowSize(1).build())
			.failoverConfig(FailoverConfig.builder().unhealthyCooldownMs(0L).build())
			.metrics(metrics)
			.build();

		ChatResponse resp = util.gateway().chat(req());
		assertEquals("B:ok", resp.firstText());
		assertTrue(metrics.snapshot().failureCount() >= 1);
	}

	/** 租户层装配：无租户透传；超并发抛 AiBudgetExceededException。 */
	@Test
	public void tenantLayerPassThroughAndBudgetEnforced() {
		TenantManager tm = new TenantManager();
		tm.configureTenant("t1", new TenantConfig(0.0d, 0L, 1, Duration.ofDays(1), 0));
		ClientRegistry reg = new ClientRegistry();
		reg.register("openai", new FakeClient("x"));
		GatewayUtil util = GatewayUtil.builder()
			.registry(reg)
			.tenant(tm, new CostAggregator(), null)
			.build();

		ChatResponse resp = util.gateway().chat(req());
		assertEquals("x:ok", resp.firstText());

		ChatRequest t1 = ChatRequest.builder().model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.extra(RequestContext.EXTRA_TENANT_ID, "t1").build();
		util.gateway().chat(t1);
		assertThrows(AiBudgetExceededException.class, () -> util.gateway().chat(t1));
	}

	/** 探活装配：start/stop 幂等，runOnce 摘除不健康实例。 */
	@Test
	public void healthProbeAssemblyStartStopAndRunOnce() {
		ClientRegistry reg = new ClientRegistry();
		FakeClient bad = new FakeClient("bad");
		reg.register("openai", "bad", bad);
		HealthProbe probe = (client, platform, instanceId) -> false;
		GatewayUtil util = GatewayUtil.builder()
			.registry(reg)
			.healthProbe(probe)
			.healthIntervalMs(5_000L)
			.build();

		util.startHealthMonitor();
		util.startHealthMonitor();
		HealthMonitor monitor = util.healthMonitor();
		assertNotNull(monitor);
		monitor.runOnce();
		assertFalse(reg.isHealthy("openai", "bad"));
		util.stopHealthMonitor();
		util.stopHealthMonitor();
	}

	/** 全部 Builder setter 注入生效。 */
	@Test
	public void builderAllSettersApplied() {
		ClientRegistry reg = new ClientRegistry();
		reg.register("openai", new FakeClient("x"));
		GatewayUtil util = GatewayUtil.builder()
			.registry(reg)
			.strategy(new RoundRobinStrategy())
			.failoverConfig(FailoverConfig.defaults())
			.circuitBreakers(CircuitBreakerRegistry.defaults())
			.metrics(new GatewayMetrics())
			.auditLogger(new GatewayAuditLogger())
			.costCalculator(new CostCalculator(PriceCatalog.defaults()))
			.build();
		ChatResponse resp = util.gateway().chat(req());
		assertEquals("x:ok", resp.firstText());
	}

	/** 未装配探活时 start/stop 无操作，healthMonitor() 返回 null。 */
	@Test
	public void startStopWithoutProbeIsNoOp() {
		GatewayUtil util = GatewayUtil.builder().registry(new ClientRegistry()).build();
		util.startHealthMonitor();
		util.stopHealthMonitor();
		assertNull(util.healthMonitor());
	}
}
