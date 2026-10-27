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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.Test;

import com.sure.ai.client.resilience.CircuitBreaker;
import com.sure.ai.cost.CostCalculator;
import com.sure.ai.cost.PriceCatalog;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;

/**
 * {@link GatewayClient} 生产化集成测试（零网络）：熔断过滤 / 全熔断快速失败 / 流式首段前转移 /
 * 指标与审计钩子 / 健康视图 / 成本估算。
 *
 * <p>确定性策略按平台名排序选第一个；熔断测试统一用阈值 1 的配置快速开门，
 * 并把不健康冷却置 0ms（失败后立即可再次被路由），避免真实时序等待。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public class GatewayClientProductionTest {

	/** 确定性策略：按平台名排序后选第一个健康候选。 */
	private static final class FirstStrategy implements RoutingStrategy {
		@Override
		public ClientCandidate select(RequestContext context, List<ClientCandidate> candidates) {
			return candidates.stream()
				.sorted((x, y) -> (x.platform() + x.instanceId())
					.compareTo(y.platform() + y.instanceId()))
				.findFirst()
				.orElse(null);
		}
	}

	/** JUL Handler 捕获格式化后的审计行。 */
	private static final class CapturingHandler extends java.util.logging.Handler {
		private final List<String> lines;

		CapturingHandler(List<String> lines) {
			this.lines = lines;
		}

		@Override
		public void publish(LogRecord record) {
			this.lines.add(record.getMessage());
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	}

	/** 阈值 1、窗口 1 的熔断注册表：一次失败即 OPEN。 */
	private static CircuitBreakerRegistry breakerThreshold1() {
		return new CircuitBreakerRegistry(
			CircuitBreakerConfig.builder().failureThreshold(1).windowSize(1).build());
	}

	/** 不健康冷却 0ms 的故障转移配置（失败后立即可再次路由该实例）。 */
	private static FailoverConfig noCooldown() {
		return FailoverConfig.builder().unhealthyCooldownMs(0L).build();
	}

	private static ChatRequest req() {
		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
	}

	/** 熔断 OPEN 的实例不再被调用，请求转移到健康候选。 */
	@Test
	public void circuitBreakerFiltersOpenInstance() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B");
		registry.register("a", a);
		registry.register("b", b);
		CircuitBreakerRegistry breakers = breakerThreshold1();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(), noCooldown(),
			breakers, null, null, null);

		ChatResponse resp = gw.chat(req());
		assertEquals("B:ok", resp.firstText());
		resp = gw.chat(req());
		assertEquals("B:ok", resp.firstText());
		assertEquals(1, a.chatCalls);
		assertEquals(2, b.chatCalls);
	}

	/** 全部实例熔断 OPEN：快速失败消息含 circuit breaker，且不再触碰任何实例。 */
	@Test
	public void allCircuitBreakersOpenFastFail() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t1"));
		FakeClient b = new FakeClient("B").throwOn(new AiTimeoutException("t2"));
		registry.register("a", a);
		registry.register("b", b);
		CircuitBreakerRegistry breakers = breakerThreshold1();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(), noCooldown(),
			breakers, null, null, null);

		try {
			gw.chat(req());
			fail("expected AiException");
		} catch (AiException expected) {
			// 第一次请求把两个熔断器都打开
		}
		try {
			gw.chat(req());
			fail("expected AiException");
		} catch (AiException expected) {
			assertTrue(expected.getMessage().contains("circuit breaker"));
		}
		assertEquals(1, a.chatCalls);
		assertEquals(1, b.chatCalls);
	}

	/** 熔断 onFailure / onSuccess 接线：成功与失败后快照计数变化。 */
	@Test
	public void breakerOnFailureAndOnSuccessWired() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B");
		registry.register("a", a);
		registry.register("b", b);
		CircuitBreakerRegistry breakers = breakerThreshold1();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(), noCooldown(),
			breakers, null, null, null);

		gw.chat(req());
		CircuitBreaker.Snapshot sa = breakers.snapshot("a", "default");
		assertEquals(1, sa.totalCalls());
		assertEquals(CircuitBreaker.State.OPEN, sa.state());
		CircuitBreaker.Snapshot sb = breakers.snapshot("b", "default");
		assertEquals(1, sb.totalCalls());
		assertEquals(1, sb.successCount());
	}

	/** 指标钩子：onRequestStart / Success / Failure 计数经 snapshot 断言。 */
	@Test
	public void metricsHooksCounted() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B");
		registry.register("a", a);
		registry.register("b", b);
		GatewayMetrics metrics = new GatewayMetrics();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults(), null, metrics, null, null);

		ChatResponse resp = gw.chat(req());
		assertEquals("B:ok", resp.firstText());
		GatewayMetrics.Snapshot s = metrics.snapshot();
		assertEquals(1, s.totalRequests());
		assertEquals(1, s.successCount());
		assertEquals(1, s.failureCount());
		assertEquals(0, s.circuitBreakerRejections());
	}

	/** 指标钩子：熔断拒绝计数与成功/失败分离统计。 */
	@Test
	public void metricsCircuitRejectCounted() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B");
		registry.register("a", a);
		registry.register("b", b);
		CircuitBreakerRegistry breakers = breakerThreshold1();
		GatewayMetrics metrics = new GatewayMetrics();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(), noCooldown(),
			breakers, metrics, null, null);

		gw.chat(req());
		gw.chat(req());
		GatewayMetrics.Snapshot s = metrics.snapshot();
		assertEquals(2, s.totalRequests());
		assertEquals(2, s.successCount());
		assertEquals(1, s.failureCount());
		assertEquals(2, s.circuitBreakerRejections());
	}

	/** 审计钩子：SUCCESS / FAILED 事件被捕获且含 tenant / platform。 */
	@Test
	public void auditEventsCaptured() {
		Logger julLogger = Logger.getLogger("com.sure.ai.gateway.Audit.ProductionTest");
		List<String> lines = new ArrayList<>();
		julLogger.addHandler(new CapturingHandler(lines));
		julLogger.setLevel(Level.INFO);
		GatewayAuditLogger audit = new GatewayAuditLogger(julLogger);

		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A");
		registry.register("a", a);
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults(), null, null, audit, null);

		ChatRequest request = ChatRequest.builder().model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.extra(RequestContext.EXTRA_TENANT_ID, "t1").build();
		gw.chat(request);
		assertEquals(1, lines.size());
		assertTrue(lines.get(0).contains("|t1|a|default|SUCCESS|"));

		ClientRegistry reg2 = new ClientRegistry();
		FakeClient bad = new FakeClient("bad").throwOn(new AiTimeoutException("x"));
		reg2.register("a", bad);
		GatewayClient gw2 = new GatewayClient(reg2, new FirstStrategy(),
			FailoverConfig.builder().maxAttempts(1).build(), null, null, audit, null);
		try {
			gw2.chat(req());
			fail("expected AiException");
		} catch (AiException expected) {
			// 耗尽后终态 FAILED
		}
		boolean hasFailed = false;
		for (String line : lines) {
			if (line.contains("|FAILED|")) {
				hasFailed = true;
			}
		}
		assertTrue(hasFailed);
	}

	/** 流式首段前失败：转移到下一候选并收到全部片。 */
	@Test
	public void streamFailoverBeforeFirstChunk() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B").stream("x", "y");
		registry.register("a", a);
		registry.register("b", b);
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults());

		List<String> collected = new ArrayList<>();
		gw.chatStream(req(), (ChatStreamChunk c) -> collected.add(c.deltaText()));
		assertEquals(List.of("x", "y"), collected);
		assertEquals(1, a.streamCalls);
		assertEquals(1, b.streamCalls);
	}

	/** 流式首段后失败：异常透传，不转移，第二候选未被调用。 */
	@Test
	public void streamNoFailoverAfterFirstChunk() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").stream("x", "y");
		FakeClient b = new FakeClient("B").stream("u", "v");
		registry.register("a", a);
		registry.register("b", b);
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults());

		List<String> collected = new ArrayList<>();
		try {
			gw.chatStream(req(), (ChatStreamChunk c) -> {
				collected.add(c.deltaText());
				if (collected.size() >= 2) {
					throw new IllegalStateException("downstream boom");
				}
			});
			fail("expected IllegalStateException");
		} catch (IllegalStateException expected) {
			// 首段已投递：原样抛出
		}
		assertEquals(List.of("x", "y"), collected);
		assertEquals(1, a.streamCalls);
		assertEquals(0, b.streamCalls);
	}

	/** healthView：注册表健康与熔断状态组合（OPEN 实例注册表仍判健康）。 */
	@Test
	public void healthViewAssemblesState() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B");
		registry.register("a", a);
		registry.register("b", b);
		CircuitBreakerRegistry breakers = breakerThreshold1();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(), noCooldown(),
			breakers, null, null, null);

		gw.chat(req());

		List<ClientHealthView> views = gw.healthView();
		assertEquals(2, views.size());
		ClientHealthView va = views.stream()
			.filter(v -> v.platform().equals("a")).findFirst().orElseThrow();
		assertTrue(va.healthy());
		assertEquals(CircuitBreaker.State.OPEN, va.circuitState());
		ClientHealthView vb = views.stream()
			.filter(v -> v.platform().equals("b")).findFirst().orElseThrow();
		assertTrue(vb.healthy());
		assertEquals(CircuitBreaker.State.CLOSED, vb.circuitState());
	}

	/** 未装配熔断时 healthView 的 circuitState 为 null。 */
	@Test
	public void healthViewWithoutBreakersHasNullState() {
		ClientRegistry registry = new ClientRegistry();
		registry.register("a", new FakeClient("A"));
		GatewayClient gw = new GatewayClient(registry);
		List<ClientHealthView> views = gw.healthView();
		assertEquals(1, views.size());
		assertEquals(null, views.get(0).circuitState());
		assertTrue(views.get(0).healthy());
	}

	/** 既有构造器行为不变：无生产化组件时与历史一致。 */
	@Test
	public void legacyConstructorsBehaveUnchanged() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B");
		registry.register("a", a);
		registry.register("b", b);
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults());
		ChatResponse resp = gw.chat(req());
		assertEquals("B:ok", resp.firstText());
		assertEquals(1, a.chatCalls);
		assertEquals(1, b.chatCalls);
	}

	/** 成功响应带 catalogued 模型用量时成本计入 metrics。 */
	@Test
	public void costEstimatedOnSuccessfulChat() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient gpt = new FakeClient("g") {
			@Override
			public ChatResponse chat(ChatRequest request) {
				this.chatCalls++;
				Choice choice = Choice.of(0, ChatMessage.assistant("hi"), "stop");
				return ChatResponse.of("id", "gpt-4o", List.of(choice),
					TokenUsage.of(10, 20, 30), "{}");
			}
		};
		registry.register("a", gpt);
		GatewayMetrics metrics = new GatewayMetrics();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults(), null, metrics, null,
			new CostCalculator(PriceCatalog.defaults()));

		gw.chat(req());
		GatewayMetrics.Snapshot s = metrics.snapshot();
		// gpt-4o: (10 * 0.005 + 20 * 0.020) / 1000 = 0.00045 USD
		assertEquals(0.00045d, s.estimatedCostUsd(), 1e-9);
	}

	/** 响应不带用量时成本记 0；模型名缺失时回退请求模型。 */
	@Test
	public void nullUsageYieldsZeroCost() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient noUsage = new FakeClient("n") {
			@Override
			public ChatResponse chat(ChatRequest request) {
				this.chatCalls++;
				Choice choice = Choice.of(0, ChatMessage.assistant("hi"), "stop");
				return ChatResponse.of("id", null, List.of(choice), null, "{}");
			}
		};
		registry.register("a", noUsage);
		GatewayMetrics metrics = new GatewayMetrics();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults(), null, metrics, null,
			new CostCalculator(PriceCatalog.defaults()));

		gw.chat(req());
		assertEquals(0.0d, metrics.snapshot().estimatedCostUsd(), 0.0d);
	}

	/** 流式成功路径也接通熔断 / 指标钩子。 */
	@Test
	public void streamWithComponentsHooks() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient b = new FakeClient("B").stream("x", "y");
		registry.register("b", b);
		CircuitBreakerRegistry breakers = breakerThreshold1();
		GatewayMetrics metrics = new GatewayMetrics();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults(), breakers, metrics, null, null);

		List<String> collected = new ArrayList<>();
		gw.chatStream(req(), c -> collected.add(c.deltaText()));
		assertEquals(List.of("x", "y"), collected);
		GatewayMetrics.Snapshot s = metrics.snapshot();
		assertEquals(1, s.totalRequests());
		assertEquals(1, s.successCount());
		assertEquals(1, breakers.snapshot("b", "default").totalCalls());
	}

	/** 流式不可转移异常（4xx）：记熔断 onFailure / 指标失败 / 审计 FAILED 后原样抛出。 */
	@Test
	public void streamNonRetryableThrowsImmediately() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient bad = new FakeClient("bad").throwOn(new AiAuthException("bad key", "{}"));
		registry.register("a", bad);
		CircuitBreakerRegistry breakers = breakerThreshold1();
		GatewayMetrics metrics = new GatewayMetrics();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.defaults(), breakers, metrics, null, null);

		try {
			gw.chatStream(req(), c -> { });
			fail("expected AiAuthException");
		} catch (AiAuthException expected) {
			// 首段前 4xx 不转移
		}
		assertEquals(1, metrics.snapshot().failureCount());
		assertEquals(1, breakers.snapshot("a", "default").totalCalls());
	}

	/** 流式耗尽：终态审计 FAILED 事件出现。 */
	@Test
	public void streamExhaustedAuditsFinalFailed() {
		Logger julLogger = Logger.getLogger("com.sure.ai.gateway.Audit.StreamExhausted");
		List<String> lines = new ArrayList<>();
		julLogger.addHandler(new CapturingHandler(lines));
		julLogger.setLevel(Level.INFO);
		GatewayAuditLogger audit = new GatewayAuditLogger(julLogger);

		ClientRegistry registry = new ClientRegistry();
		FakeClient bad = new FakeClient("bad").throwOn(new AiTimeoutException("x"));
		registry.register("a", bad);
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(),
			FailoverConfig.builder().maxAttempts(1).build(), null, null, audit, null);

		try {
			gw.chatStream(req(), c -> { });
			fail("expected AiException");
		} catch (AiException expected) {
			// 耗尽
		}
		boolean hasFailed = false;
		for (String line : lines) {
			if (line.contains("|FAILED|")) {
				hasFailed = true;
			}
		}
		assertTrue(hasFailed);
	}

	/** 流式全熔断：快速失败消息含 circuit breaker，实例不再被调用。 */
	@Test
	public void streamAllBreakersOpenFastFail() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("A").throwOn(new AiTimeoutException("t"));
		FakeClient b = new FakeClient("B").throwOn(new AiTimeoutException("t"));
		registry.register("a", a);
		registry.register("b", b);
		CircuitBreakerRegistry breakers = breakerThreshold1();
		GatewayClient gw = new GatewayClient(registry, new FirstStrategy(), noCooldown(),
			breakers, null, null, null);

		try {
			gw.chatStream(req(), c -> { });
			fail("expected AiException");
		} catch (AiException expected) {
			// 第一次请求把两个熔断器都打开
		}
		try {
			gw.chatStream(req(), c -> { });
			fail("expected AiException");
		} catch (AiException expected) {
			assertTrue(expected.getMessage().contains("circuit breaker"));
		}
		assertEquals(1, a.streamCalls);
		assertEquals(1, b.streamCalls);
	}

	/** 空注册表流式请求：直接耗尽抛 AiException。 */
	@Test
	public void emptyRegistryStreamThrows() {
		GatewayClient gw = new GatewayClient(new ClientRegistry());
		try {
			gw.chatStream(req(), c -> { });
			fail("expected AiException");
		} catch (AiException expected) {
			// 无候选
		}
	}

	/** 策略返回 null：chat 与 chatStream 均走耗尽分支。 */
	@Test
	public void strategyNullSelectionBreaks() {
		ClientRegistry registry = new ClientRegistry();
		registry.register("a", new FakeClient("x"));
		RoutingStrategy nullStrategy = (ctx, candidates) -> null;
		GatewayClient gw = new GatewayClient(registry, nullStrategy, FailoverConfig.defaults());
		try {
			gw.chat(req());
			fail("expected AiException");
		} catch (AiException expected) {
			// 策略无候选
		}
		try {
			gw.chatStream(req(), c -> { });
			fail("expected AiException");
		} catch (AiException expected) {
			// 策略无候选
		}
	}
}