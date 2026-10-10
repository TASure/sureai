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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.Capability;
import com.sure.ai.cost.CostAggregator;
import com.sure.ai.cost.CostCalculator;
import com.sure.ai.cost.PriceCatalog;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiBudgetExceededException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.TokenUsage;

/**
 * gateway 模块覆盖率补测：RequestContext / FailoverConfig / TenantConfig / ClientRegistry /
 * GatewayClient / 路由策略边界 / LatencyTracker / BudgetEnforcer / TenantAwareGatewayClient /
 * KeyRotatingClientDecorator / TenantManager / ClientCandidate 的未覆盖分支。
 *
 * <p>全部零真实网络，使用内存对象与 FakeClient。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class GatewayCoverageExtraTest {

	/** 构造一个最简请求。 */
	private static ChatRequest req() {
		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
	}

	// ==================== RequestContext ====================

	/** null extra 防御性拷贝为空 Map。 */
	@Test
	public void requestContextNullExtraBecomesEmpty() {
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "openai:gpt-4o", null, null);
		assertNotNull(ctx.extra());
		assertTrue(ctx.extra().isEmpty());
	}

	/** 模型名带前缀时 explicitPlatform 返回前缀、bareModel 返回裸名。 */
	@Test
	public void requestContextModelPrefixParsed() {
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "deepseek:v3", Map.of(), null);
		assertEquals("deepseek", ctx.explicitPlatform());
		assertEquals("v3", ctx.bareModel());
	}

	/** extra 中空白字符串平台名被忽略，回退模型前缀。 */
	@Test
	public void requestContextBlankExtraPlatformFallsBackToModel() {
		Map<String, Object> extra = new HashMap<>();
		extra.put(RequestContext.EXTRA_PLATFORM, "  ");
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "openai:gpt-4o", extra, null);
		assertEquals("openai", ctx.explicitPlatform());
	}

	/** 无平台前缀时 explicitPlatform 返回 null、bareModel 返回原名。 */
	@Test
	public void requestContextNoPrefix() {
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "gpt-4o", Map.of(), null);
		assertNull(ctx.explicitPlatform());
		assertEquals("gpt-4o", ctx.bareModel());
	}

	/** null 模型名返回 null。 */
	@Test
	public void requestContextNullModel() {
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, null, Map.of(), null);
		assertNull(ctx.explicitPlatform());
		assertNull(ctx.bareModel());
	}

	/** 前缀在首字符（idx==0）不视为平台前缀。 */
	@Test
	public void requestContextLeadingColonNotAPrefix() {
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, ":weird", Map.of(), null);
		assertNull(ctx.explicitPlatform());
		assertEquals(":weird", ctx.bareModel());
	}

	// ==================== FailoverConfig ====================

	/** 5xx 可转移、4xx 不可转移、timeout 可转移、非 AiException 可转移。 */
	@Test
	public void failoverConfigIsRetryableClassification() {
		FailoverConfig cfg = FailoverConfig.defaults();
		assertTrue(cfg.isRetryable(new AiApiException(500, "s", "boom", "{}")));
		assertTrue(cfg.isRetryable(new AiApiException(503, "s", "boom", "{}")));
		assertFalse(cfg.isRetryable(new AiApiException(404, "s", "boom", "{}")));
		assertFalse(cfg.isRetryable(new AiApiException(401, "s", "boom", "{}")));
		assertTrue(cfg.isRetryable(new AiTimeoutException("timeout")));
		assertTrue(cfg.isRetryable(new java.io.IOException("conn reset")));
		assertFalse(cfg.isRetryable(new AiException("generic ai")));
	}

	/** cause 链上的 5xx 也判定为可转移。 */
	@Test
	public void failoverConfigWrapsCauseChain() {
		FailoverConfig cfg = FailoverConfig.defaults();
		AiApiException server = new AiApiException(502, "s", "boom", "{}");
		assertTrue(cfg.isRetryable(new RuntimeException("wrapped", server)));
	}

	/** 自定义 retryable 异常类型加入后命中即返回 true。 */
	@Test
	public void failoverConfigCustomRetryableException() {
		FailoverConfig cfg = FailoverConfig.builder()
			.retryable(IllegalStateException.class)
			.build();
		assertTrue(cfg.isRetryable(new IllegalStateException("custom")));
		// IllegalArgumentException 非 AiException，默认也视为可转移
		assertTrue(cfg.isRetryable(new IllegalArgumentException("other")));
	}

	/** maxAttempts<1 抛 IllegalArgumentException。 */
	@Test
	public void failoverConfigRejectsMaxAttemptsBelowOne() {
		assertThrows(IllegalArgumentException.class,
			() -> FailoverConfig.builder().maxAttempts(0).build());
	}

	/** listener 传 null 时回退 NoopListener。 */
	@Test
	public void failoverConfigNullListenerBecomesNoop() {
		FailoverConfig cfg = FailoverConfig.builder().listener(null).build();
		assertNotNull(cfg.listener());
		cfg.listener().onExhausted(List.of(), null);
		cfg.listener().onFailover("a", "i1", "b", "i2", new Exception("x"), 1);
	}

	/** 默认配置字段值。 */
	@Test
	public void failoverConfigDefaults() {
		FailoverConfig cfg = FailoverConfig.defaults();
		assertEquals(3, cfg.maxAttempts());
		assertEquals(30_000L, cfg.unhealthyCooldownMs());
		assertNotNull(cfg.listener());
	}

	// ==================== TenantConfig ====================

	/** 三个非负校验任一为负即抛异常。 */
	@Test
	public void tenantConfigValidationRejectsNegative() {
		assertThrows(IllegalArgumentException.class,
			() -> new TenantConfig(-1, 0, 0, null));
		assertThrows(IllegalArgumentException.class,
			() -> new TenantConfig(0, -1, 0, null));
		assertThrows(IllegalArgumentException.class,
			() -> new TenantConfig(0, 0, -1, null));
	}

	/** null budgetPeriod 回退默认 1 天。 */
	@Test
	public void tenantConfigNullPeriodFallback() {
		TenantConfig cfg = new TenantConfig(0, 0, 0, null);
		assertEquals(Duration.ofDays(1), cfg.budgetPeriod());
	}

	/** unlimited() 三标志均 false。 */
	@Test
	public void tenantConfigUnlimited() {
		TenantConfig cfg = TenantConfig.unlimited();
		assertFalse(cfg.limitedCost());
		assertFalse(cfg.limitedTokens());
		assertFalse(cfg.limitedQps());
	}

	/** Builder 全链路构建。 */
	@Test
	public void tenantConfigBuilder() {
		TenantConfig cfg = TenantConfig.builder()
			.maxCostPerPeriod(10.0)
			.maxTokensPerPeriod(1000L)
			.rateLimitQps(5)
			.budgetPeriod(Duration.ofHours(2))
			.build();
		assertEquals(10.0, cfg.maxCostPerPeriod(), 0.0);
		assertEquals(1000L, cfg.maxTokensPerPeriod());
		assertEquals(5, cfg.rateLimitQps());
		assertEquals(Duration.ofHours(2), cfg.budgetPeriod());
		assertTrue(cfg.limitedCost());
		assertTrue(cfg.limitedTokens());
		assertTrue(cfg.limitedQps());
	}

	// ==================== ClientCandidate ====================

	/** null capabilities 防御性为空集合。 */
	@Test
	public void clientCandidateNullCapabilitiesBecomesEmpty() {
		ClientCandidate c = new ClientCandidate(new FakeClient("x"), "p", "i", null, 1.0, null);
		assertNotNull(c.capabilities());
		assertTrue(c.capabilities().isEmpty());
		assertFalse(c.has(Capability.CHAT));
	}

	// ==================== ClientRegistry ====================

	/** 4 参 register 走默认权重与默认模型。 */
	@Test
	public void clientRegistryRegisterFourArg() {
		ClientRegistry reg = new ClientRegistry();
		FakeClient c = new FakeClient("c");
		reg.register("openai", "i1", c, Set.of(Capability.CHAT));
		assertSame(c, reg.byName("openai", "i1"));
	}

	/** weight<=0 抛 IllegalArgumentException。 */
	@Test
	public void clientRegistryRejectsNonPositiveWeight() {
		ClientRegistry reg = new ClientRegistry();
		assertThrows(IllegalArgumentException.class, () -> reg.register(
			"p", "i", new FakeClient("x"), Set.of(), 0.0, null));
	}

	/** byName 未注册返回 null。 */
	@Test
	public void clientRegistryByNameUnknownReturnsNull() {
		ClientRegistry reg = new ClientRegistry();
		assertNull(reg.byName("nope", "i"));
		assertNull(reg.byName("openai", "missing"));
	}

	/** remove 最后一个实例时平台整体移除。 */
	@Test
	public void clientRegistryRemoveLastInstanceDropsPlatform() {
		ClientRegistry reg = new ClientRegistry();
		reg.register("openai", "i1", new FakeClient("c1"));
		reg.remove("openai", "i1");
		assertTrue(reg.byPlatform("openai").isEmpty());
	}

	/** removeAll 整平台移除。 */
	@Test
	public void clientRegistryRemoveAll() {
		ClientRegistry reg = new ClientRegistry();
		reg.register("openai", "i1", new FakeClient("c1"));
		reg.register("openai", "i2", new FakeClient("c2"));
		reg.removeAll("openai");
		assertTrue(reg.byPlatform("openai").isEmpty());
	}

	/** markUnhealthy / isHealthy / markHealthy 往返。 */
	@Test
	public void clientRegistryHealthLifecycle() {
		ClientRegistry reg = new ClientRegistry();
		reg.register("openai", "i1", new FakeClient("c1"));
		assertTrue(reg.isHealthy("openai", "i1"));
		reg.markUnhealthy("openai", "i1", 60_000L);
		assertFalse(reg.isHealthy("openai", "i1"));
		reg.markHealthy("openai", "i1");
		assertTrue(reg.isHealthy("openai", "i1"));
	}

	/** byCapability 过滤。 */
	@Test
	public void clientRegistryByCapability() {
		ClientRegistry reg = new ClientRegistry();
		FakeClient chat = new FakeClient("chat");
		FakeClient embed = new FakeClient("embed");
		reg.register("a", "i1", chat, Set.of(Capability.CHAT));
		reg.register("b", "i1", embed, Set.of(Capability.EMBED));
		assertEquals(1, reg.byCapability(Capability.EMBED).size());
		assertEquals(1, reg.byCapability(Capability.CHAT).size());
	}

	/** healthyCandidates 只返回健康实例。 */
	@Test
	public void clientRegistryHealthyCandidatesFiltersUnhealthy() {
		ClientRegistry reg = new ClientRegistry();
		reg.register("openai", "i1", new FakeClient("c1"));
		reg.register("openai", "i2", new FakeClient("c2"));
		assertEquals(2, reg.healthyCandidates().size());
		reg.markUnhealthy("openai", "i1", 60_000L);
		assertEquals(1, reg.healthyCandidates().size());
	}

	// ==================== GatewayClient ====================

	/** 空注册表时 chat 抛 AiException。 */
	@Test
	public void gatewayClientEmptyRegistryThrows() {
		ClientRegistry reg = new ClientRegistry();
		GatewayClient gw = new GatewayClient(reg);
		assertThrows(AiException.class, () -> gw.chat(req()));
	}

	/** 流式请求构建 CHAT_STREAM 上下文。 */
	@Test
	public void gatewayClientStreamUsesStreamCapability() {
		ClientRegistry reg = new ClientRegistry();
		FakeClient c = new FakeClient("c").stream("a", "b");
		reg.register("openai", "default", c);
		GatewayClient gw = new GatewayClient(reg);
		StringBuilder out = new StringBuilder();
		gw.chatStream(ChatRequest.builder().model("m").stream(true)
			.messages(List.of(ChatMessage.user("hi"))).build(),
			ch -> out.append(ch.deltaText()));
		assertEquals("ab", out.toString());
	}

	/** name() 返回 "gateway"。 */
	@Test
	public void gatewayClientName() {
		GatewayClient gw = new GatewayClient(new ClientRegistry());
		assertEquals("gateway", gw.name());
	}

	/** close() 关闭全部注册客户端。 */
	@Test
	public void gatewayClientCloseClosesAll() {
		ClientRegistry reg = new ClientRegistry();
		FakeClient c1 = new FakeClient("c1");
		FakeClient c2 = new FakeClient("c2");
		reg.register("openai", "i1", c1);
		reg.register("deepseek", "i1", c2);
		GatewayClient gw = new GatewayClient(reg);
		gw.close();
		assertTrue(c1.closed);
		assertTrue(c2.closed);
	}

	/** 不可转移异常（4xx）直接抛出，不故障转移。 */
	@Test
	public void gatewayClientNonRetryableThrowsImmediately() {
		ClientRegistry reg = new ClientRegistry();
		FakeClient bad = new FakeClient("bad").throwOn(new AiApiException(401, "s", "boom", "{}"));
		reg.register("openai", "i1", bad);
		GatewayClient gw = new GatewayClient(reg);
		assertThrows(AiApiException.class, () -> gw.chat(req()));
		assertEquals(1, bad.chatCalls);
	}

	// ==================== 路由策略边界 ====================

	/** null / 空候选返回 null。 */
	@Test
	public void routingStrategiesEmptyCandidatesReturnNull() {
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "m", Map.of(), null);
		assertNull(new RoundRobinStrategy().select(ctx, null));
		assertNull(new RoundRobinStrategy().select(ctx, List.of()));
		assertNull(new WeightedRoutingStrategy().select(ctx, null));
		assertNull(new WeightedRoutingStrategy().select(ctx, List.of()));
		assertNull(new LowestLatencyStrategy(null).select(ctx, null));
		assertNull(new LowestCostStrategy(null).select(ctx, null));
		assertNull(new ExplicitRoutingStrategy(null).select(ctx, null));
		assertNull(new CapabilityRoutingStrategy(null).select(ctx, null));
	}

	/** CapabilityRoutingStrategy: 无 capability 时直接委托。 */
	@Test
	public void capabilityRoutingNullCapabilityDelegates() {
		ClientCandidate c = new ClientCandidate(new FakeClient("x"), "p", "i",
			Set.of(Capability.CHAT), 1.0, null);
		CapabilityRoutingStrategy s = new CapabilityRoutingStrategy(new RoundRobinStrategy());
		RequestContext ctx = new RequestContext("chat", null, "m", Map.of(), null);
		assertSame(c, s.select(ctx, List.of(c)));
	}

	/** CapabilityRoutingStrategy: 无候选匹配能力返回 null。 */
	@Test
	public void capabilityRoutingNoMatchReturnsNull() {
		ClientCandidate chatOnly = new ClientCandidate(new FakeClient("x"), "p", "i",
			Set.of(Capability.CHAT), 1.0, null);
		CapabilityRoutingStrategy s = new CapabilityRoutingStrategy(new RoundRobinStrategy());
		RequestContext ctx = new RequestContext("chat", Capability.EMBED, "m", Map.of(), null);
		assertNull(s.select(ctx, List.of(chatOnly)));
	}

	/** ExplicitRoutingStrategy: 无匹配平台返回 null。 */
	@Test
	public void explicitRoutingNoMatchReturnsNull() {
		ClientCandidate c = new ClientCandidate(new FakeClient("x"), "openai", "i",
			Set.of(Capability.CHAT), 1.0, null);
		Map<String, Object> extra = new HashMap<>();
		extra.put(RequestContext.EXTRA_PLATFORM, "deepseek");
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "m", extra, null);
		assertNull(new ExplicitRoutingStrategy(new RoundRobinStrategy()).select(ctx, List.of(c)));
	}

	/** LowestCostStrategy: 模型无价格视为最贵，选中有价格者。 */
	@Test
	public void lowestCostUnknownModelIsMostExpensive() {
		ClientCandidate known = new ClientCandidate(new FakeClient("k"), "k", "i",
			Set.of(Capability.CHAT), 1.0, "gpt-4o-mini");
		ClientCandidate unknown = new ClientCandidate(new FakeClient("u"), "u", "i",
			Set.of(Capability.CHAT), 1.0, "unknown-model");
		LowestCostStrategy s = new LowestCostStrategy(null);
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "m", Map.of(), null);
		assertSame(known, s.select(ctx, List.of(known, unknown)));
	}

	/** LowestLatencyStrategy: null tracker 用默认值。 */
	@Test
	public void lowestLatencyNullTrackerUsesDefault() {
		ClientCandidate a = new ClientCandidate(new FakeClient("a"), "a", "i",
			Set.of(Capability.CHAT), 1.0, null);
		ClientCandidate b = new ClientCandidate(new FakeClient("b"), "b", "i",
			Set.of(Capability.CHAT), 1.0, null);
		LowestLatencyStrategy s = new LowestLatencyStrategy(null);
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "m", Map.of(), null);
		// 两个都无样本，全部用默认延迟，选中第一个
		assertSame(a, s.select(ctx, List.of(a, b)));
	}

	// ==================== LatencyTracker ====================

	/** 超过 MAX_SAMPLES 时淘汰最旧。 */
	@Test
	public void latencyTrackerEvictsBeyondMaxSamples() {
		LatencyTracker t = new LatencyTracker();
		for (int i = 0; i < LatencyTracker.MAX_SAMPLES + 5; i++) {
			t.record("p", "i", 10L);
		}
		// 仍能取到平均（不抛异常）
		double avg = t.averageLatency("p", "i");
		assertTrue(avg > 0);
	}

	/** 无样本返回默认延迟。 */
	@Test
	public void latencyTrackerNoSamplesReturnsDefault() {
		LatencyTracker t = new LatencyTracker();
		assertEquals(LatencyTracker.DEFAULT_LATENCY_MS, t.averageLatency("p", "i"), 0.0);
	}

	/** reset 清空全部样本。 */
	@Test
	public void latencyTrackerReset() {
		LatencyTracker t = new LatencyTracker();
		t.record("p", "i", 100L);
		t.reset();
		assertEquals(LatencyTracker.DEFAULT_LATENCY_MS, t.averageLatency("p", "i"), 0.0);
	}

	// ==================== BudgetEnforcer ====================

	/** 构造一个不配置任何租户的 enforcer。 */
	private static BudgetEnforcer newEnforcer(TenantManager tm, CostAggregator agg) {
		return new BudgetEnforcer(tm, agg, () -> 1_000_000L);
	}

	/** 空 CostAggregator 占位。 */
	private static CostAggregator emptyAggregator() {
		return new CostAggregator();
	}

	/** blank / null tenantId 直接放行。 */
	@Test
	public void budgetEnforcerBlankTenantPasses() {
		TenantManager tm = new TenantManager();
		BudgetEnforcer e = newEnforcer(tm, emptyAggregator());
		e.checkBeforeCall(null, 0.0);
		e.checkBeforeCall("  ", 0.0);
	}

	/** 未配置 cfg 的租户放行。 */
	@Test
	public void budgetEnforcerUnknownTenantPasses() {
		TenantManager tm = new TenantManager();
		BudgetEnforcer e = newEnforcer(tm, emptyAggregator());
		e.checkBeforeCall("ghost", 0.0);
	}

	/** token 超限抛 AiBudgetExceededException。 */
	@Test
	public void budgetEnforcerTokenLimitExceeded() {
		TenantManager tm = new TenantManager();
		tm.configureTenant("t1", new TenantConfig(0, 100L, 0, Duration.ofDays(1)));
		BudgetEnforcer e = newEnforcer(tm, emptyAggregator());
		e.recordAfterCall("t1", "m", TokenUsage.of(50, 50, 100), 0.0);
		assertThrows(AiBudgetExceededException.class, () -> e.checkBeforeCall("t1", 0.0));
	}

	/** cost 超限抛 AiBudgetExceededException。 */
	@Test
	public void budgetEnforcerCostLimitExceeded() {
		TenantManager tm = new TenantManager();
		tm.configureTenant("t2", new TenantConfig(1.0, 0, 0, Duration.ofDays(1)));
		BudgetEnforcer e = newEnforcer(tm, emptyAggregator());
		e.recordAfterCall("t2", "m", TokenUsage.of(0, 0, 0), 2.0);
		assertThrows(AiBudgetExceededException.class, () -> e.checkBeforeCall("t2", 0.0));
	}

	/** QPS 超限抛 AiBudgetExceededException。 */
	@Test
	public void budgetEnforcerQpsLimitExceeded() {
		TenantManager tm = new TenantManager();
		tm.configureTenant("t3", new TenantConfig(0, 0, 1, Duration.ofDays(1)));
		BudgetEnforcer e = newEnforcer(tm, emptyAggregator());
		e.checkBeforeCall("t3", 0.0);
		assertThrows(AiBudgetExceededException.class, () -> e.checkBeforeCall("t3", 0.0));
	}

	/** recordAfterCall: null usage / blank tenantId 安全。 */
	@Test
	public void budgetEnforcerRecordAfterCallNulls() {
		TenantManager tm = new TenantManager();
		BudgetEnforcer e = newEnforcer(tm, emptyAggregator());
		e.recordAfterCall(null, "m", null, 0.0);
		e.recordAfterCall("  ", "m", null, 0.0);
		e.recordAfterCall("ghost", null, null, 0.0);
		TenantUsage u = e.currentUsage("ghost");
		assertEquals(1, u.requestsInWindow());
		assertEquals(0.0, u.costUsed(), 0.0);
	}

	/** currentUsage: blank tenantId 返回全零。 */
	@Test
	public void budgetEnforcerCurrentUsageBlankTenant() {
		TenantManager tm = new TenantManager();
		BudgetEnforcer e = newEnforcer(tm, emptyAggregator());
		TenantUsage u = e.currentUsage("  ");
		assertEquals(0, u.requestsInWindow());
		assertEquals(0.0, u.costUsed(), 0.0);
	}

	/** currentUsage: 未配置 cfg 返回零限额。 */
	@Test
	public void budgetEnforcerCurrentUsageUnknownTenant() {
		TenantManager tm = new TenantManager();
		BudgetEnforcer e = newEnforcer(tm, emptyAggregator());
		e.recordAfterCall("ghost", "m", TokenUsage.of(1, 1, 2), 0.5);
		TenantUsage u = e.currentUsage("ghost");
		assertEquals(1, u.requestsInWindow());
		assertEquals(0.0, u.costLimit(), 0.0);
	}

	/** 默认构造器用系统时钟。 */
	@Test
	public void budgetEnforcerDefaultCtorUsesSystemClock() {
		TenantManager tm = new TenantManager();
		BudgetEnforcer e = new BudgetEnforcer(tm, emptyAggregator());
		e.checkBeforeCall("x", 0.0);
	}

	// ==================== TenantAwareGatewayClient ====================

	/** name() 返回 "tenant-aware"。 */
	@Test
	public void tenantAwareName() {
		TenantAwareGatewayClient c = new TenantAwareGatewayClient(new FakeClient("x"),
			new BudgetEnforcer(new TenantManager(), emptyAggregator()));
		assertEquals("tenant-aware", c.name());
	}

	/** 带 tenantId 的 chat 走事前检查 + 事后记录（含成本计算）。 */
	@Test
	public void tenantAwareChatWithTenantAndCost() {
		TenantManager tm = new TenantManager();
		tm.configureTenant("t1", TenantConfig.unlimited());
		BudgetEnforcer enforcer = new BudgetEnforcer(tm, emptyAggregator());
		FakeClient delegate = new FakeClient("d");
		CostCalculator calc = new CostCalculator(PriceCatalog.defaults());
		TenantAwareGatewayClient c = new TenantAwareGatewayClient(delegate, enforcer, calc);

		ChatRequest request = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi")))
			.extra(RequestContext.EXTRA_TENANT_ID, "t1").build();

		ChatResponse resp = c.chat(request);
		assertEquals("d:ok", resp.firstText());
		TenantUsage u = enforcer.currentUsage("t1");
		assertEquals(1, u.requestsInWindow());
		assertTrue(u.tokensUsed() > 0);
	}

	/** 无 tenantId 透传。 */
	@Test
	public void tenantAwareChatWithoutTenantPassThrough() {
		TenantManager tm = new TenantManager();
		BudgetEnforcer enforcer = new BudgetEnforcer(tm, emptyAggregator());
		FakeClient delegate = new FakeClient("d");
		TenantAwareGatewayClient c = new TenantAwareGatewayClient(delegate, enforcer);
		ChatResponse resp = c.chat(req());
		assertEquals("d:ok", resp.firstText());
	}

	/** 流式调用只做事前检查。 */
	@Test
	public void tenantAwareStreamOnlyPreCheck() {
		TenantManager tm = new TenantManager();
		tm.configureTenant("t1", TenantConfig.unlimited());
		BudgetEnforcer enforcer = new BudgetEnforcer(tm, emptyAggregator());
		FakeClient delegate = new FakeClient("d").stream("a", "b");
		TenantAwareGatewayClient c = new TenantAwareGatewayClient(delegate, enforcer);

		ChatRequest request = ChatRequest.builder().model("m").stream(true)
			.messages(List.of(ChatMessage.user("hi")))
			.extra(RequestContext.EXTRA_TENANT_ID, "t1").build();

		StringBuilder out = new StringBuilder();
		c.chatStream(request, ch -> out.append(ch.deltaText()));
		assertEquals("ab", out.toString());
	}

	/** close() 关闭 delegate。 */
	@Test
	public void tenantAwareClose() {
		FakeClient delegate = new FakeClient("d");
		TenantAwareGatewayClient c = new TenantAwareGatewayClient(delegate,
			new BudgetEnforcer(new TenantManager(), emptyAggregator()));
		c.close();
		assertTrue(delegate.closed);
	}

	// ==================== KeyRotatingClientDecorator 补充分支 ====================

	/** name() 返回 "key-rotating"。 */
	@Test
	public void keyRotatingName() {
		KeyRotatingClientDecorator d = new KeyRotatingClientDecorator(
			new com.sure.ai.client.RoundRobinApiKeyProvider(List.of("k1")),
			k -> new FakeClient(k));
		assertEquals("key-rotating", d.name());
	}

	/** 流式调用遇不可转移异常直接抛出。 */
	@Test
	public void keyRotatingStreamNonRotatableThrows() {
		FakeClient k1 = new FakeClient("k1").throwOn(new AiApiException(500, "s", "boom", "{}"));
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", k1);
		KeyRotatingClientDecorator d = new KeyRotatingClientDecorator(
			new com.sure.ai.client.RoundRobinApiKeyProvider(List.of("k1")), map::get);
		assertThrows(AiApiException.class, () -> d.chatStream(req(), ch -> { }));
	}

	/** 流式调用全部 key 耗尽抛 AiException。 */
	@Test
	public void keyRotatingStreamAllKeysExhausted() {
		FakeClient k1 = new FakeClient("k1").throwOn(new AiAuthException("bad", "{}"));
		FakeClient k2 = new FakeClient("k2").throwOn(new AiAuthException("bad", "{}"));
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", k1);
		map.put("k2", k2);
		KeyRotatingClientDecorator d = new KeyRotatingClientDecorator(
			new com.sure.ai.client.RoundRobinApiKeyProvider(List.of("k1", "k2")), map::get);
		try {
			d.chatStream(req(), ch -> { });
			fail("expected AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("2"));
		}
	}

	/** close() 关闭缓存客户端并清空缓存。 */
	@Test
	public void keyRotatingCloseClosesCachedClients() {
		FakeClient k1 = new FakeClient("k1");
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", k1);
		KeyRotatingClientDecorator d = new KeyRotatingClientDecorator(
			new com.sure.ai.client.RoundRobinApiKeyProvider(List.of("k1")), map::get);
		d.chat(req());
		d.close();
		assertTrue(k1.closed);
	}

	/** propagate: 非 RuntimeException 包成 AiException。 */
	@Test
	public void keyRotatingPropagateCheckedException() {
		// 用自定义 rotateOn=false 的谓词，让 checked exception 走到 propagate
		AiClient throwing = new AiClient() {
			@Override public String name() { return "thrower"; }
			@Override public ChatResponse chat(ChatRequest request) {
				throw new RuntimeException(new java.io.IOException("io"));
			}
			@Override public void chatStream(ChatRequest request,
					java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> c) {
				throw new RuntimeException(new java.io.IOException("io"));
			}
			@Override public void close() { }
		};
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", throwing);
		KeyRotatingClientDecorator d = new KeyRotatingClientDecorator(
			new com.sure.ai.client.RoundRobinApiKeyProvider(List.of("k1")),
			map::get, t -> false);
		// rotateOn=false，RuntimeException 原样抛出
		assertThrows(RuntimeException.class, () -> d.chat(req()));
	}

	/** 全参构造器 null 校验。 */
	@Test
	public void keyRotatingCtorNullChecks() {
		assertThrows(NullPointerException.class,
			() -> new KeyRotatingClientDecorator(null, k -> new FakeClient(k)));
		assertThrows(NullPointerException.class,
			() -> new KeyRotatingClientDecorator(
				new com.sure.ai.client.RoundRobinApiKeyProvider(List.of("k1")), null));
	}

	// ==================== TenantManager ====================

	/** configFor(null) 返回 null。 */
	@Test
	public void tenantManagerConfigForNull() {
		TenantManager tm = new TenantManager();
		assertNull(tm.configFor(null));
		assertNull(tm.resolveTenant(null));
		tm.removeVirtualKey(null);
	}

	/** resolveTenant / removeVirtualKey 往返。 */
	@Test
	public void tenantManagerVirtualKeyRoundTrip() {
		TenantManager tm = new TenantManager();
		tm.registerVirtualKey("vk-1", "tenant-a");
		assertEquals("tenant-a", tm.resolveTenant("vk-1"));
		tm.removeVirtualKey("vk-1");
		assertNull(tm.resolveTenant("vk-1"));
	}
}
