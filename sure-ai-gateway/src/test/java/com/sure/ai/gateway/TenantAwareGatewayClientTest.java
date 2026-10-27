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

import java.util.List;

import org.junit.Test;

import com.sure.ai.cost.CostAggregator;
import com.sure.ai.exception.AiBudgetExceededException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.TokenUsage;

/**
 * {@link TenantAwareGatewayClient} 端到端测试（包装真实 {@link GatewayClient} + FakeClient，零网络）。
 *
 * @author sureai
 * @since 1.6.0
 */
public class TenantAwareGatewayClientTest {

	/** 构造带 tenantId 的请求。 */
	private static ChatRequest req(String tenantId) {
		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.extra(RequestContext.EXTRA_TENANT_ID, tenantId)
			.build();
	}

	/**
	 * 租户超限后通过 GatewayClient 调用抛 AiBudgetExceededException。
	 */
	@Test
	public void testTenantBudgetEnforced() {
		TenantManager mgr = new TenantManager();
		mgr.configureTenant("t1", TenantConfig.builder().maxCostPerPeriod(1.0).build());
		CostAggregator agg = new CostAggregator();
		BudgetEnforcer enforcer = new BudgetEnforcer(mgr, agg);

		// 预先把 t1 用量打到超限
		enforcer.recordAfterCall("t1", "gpt-4o", TokenUsage.of(0, 0, 0), 5.0);

		ClientRegistry registry = new ClientRegistry();
		FakeClient downstream = new FakeClient("downstream");
		registry.register("openai", downstream);
		GatewayClient gw = new GatewayClient(registry);
		TenantAwareGatewayClient client = new TenantAwareGatewayClient(gw, enforcer);

		try {
			client.chat(req("t1"));
			fail("expected AiBudgetExceededException");
		} catch (AiBudgetExceededException ex) {
			assertEquals("t1", ex.tenantId());
			assertTrue(ex.getMessage().contains("exceeded budget"));
		}
		// 超限后下游根本没被调用
		assertEquals(0, downstream.chatCalls);
	}

	/**
	 * 调用成功后 CostAggregator 记录了该租户的成本。
	 */
	@Test
	public void testTenantUsageRecorded() {
		TenantManager mgr = new TenantManager();
		mgr.configureTenant("t1", TenantConfig.builder()
			.maxCostPerPeriod(1_000.0).maxTokensPerPeriod(1_000_000).build());
		CostAggregator agg = new CostAggregator();
		BudgetEnforcer enforcer = new BudgetEnforcer(mgr, agg);

		ClientRegistry registry = new ClientRegistry();
		FakeClient downstream = new FakeClient("downstream");
		registry.register("openai", downstream);
		GatewayClient gw = new GatewayClient(registry);
		TenantAwareGatewayClient client = new TenantAwareGatewayClient(gw, enforcer);

		client.chat(req("t1"));

		// 下游确实被调用了一次
		assertEquals(1, downstream.chatCalls);
		// 预算执行器记录了用量（FakeClient 返回 TokenUsage(1,1,2)）
		TenantUsage u = enforcer.currentUsage("t1");
		assertEquals(2L, u.tokensUsed());
		assertEquals(1, u.requestsInWindow());
		// 共享 CostAggregator 也记了一条
		assertEquals(1, agg.tenantSummary("t1").totalCalls());
	}

	/** 构造一个 t1 并发上限为 maxConcurrency 的客户端夹具。 */
	private static ClientFixture fixture(int maxConcurrency) {
		TenantManager mgr = new TenantManager();
		mgr.configureTenant("t1", TenantConfig.builder().maxConcurrency(maxConcurrency).build());
		CostAggregator agg = new CostAggregator();
		BudgetEnforcer enforcer = new BudgetEnforcer(mgr, agg);
		FakeClient downstream = new FakeClient("downstream");
		TenantAwareGatewayClient client = new TenantAwareGatewayClient(downstream, enforcer);
		return new ClientFixture(enforcer, downstream, client);
	}

	/**
	 * 并发位被占满时 chat 抛 AiBudgetExceededException（消息含 max 值），下游未被触达。
	 */
	@Test
	public void testChatRejectedWhenConcurrencyFull() {
		ClientFixture f = fixture(1);
		// 手动占满唯一并发位
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));

		try {
			f.client.chat(req("t1"));
			fail("expected AiBudgetExceededException (concurrency)");
		} catch (AiBudgetExceededException ex) {
			assertEquals("t1", ex.tenantId());
			assertTrue(ex.getMessage().contains("concurrency"));
			assertTrue(ex.getMessage().contains("max=1"));
		}
		assertEquals(0, f.downstream.chatCalls);
	}

	/**
	 * 正常调用完成后 finally 释放并发位：紧接着第二次调用仍成功（证明许可已归还）。
	 */
	@Test
	public void testChatAcquireReleasePairing() {
		ClientFixture f = fixture(1);
		f.client.chat(req("t1"));
		assertEquals(1, f.downstream.chatCalls);
		// 若 finally 未释放，第二次会因并发已满抛异常；此处成功即证明已配对释放
		f.client.chat(req("t1"));
		assertEquals(2, f.downstream.chatCalls);
	}

	/**
	 * 并发位被占满时 chatStream 同样抛 AiBudgetExceededException。
	 */
	@Test
	public void testChatStreamRejectedWhenConcurrencyFull() {
		ClientFixture f = fixture(1);
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));

		try {
			f.client.chatStream(req("t1"), c -> { });
			fail("expected AiBudgetExceededException (concurrency)");
		} catch (AiBudgetExceededException ex) {
			assertTrue(ex.getMessage().contains("concurrency"));
		}
	}

	/**
	 * 正常流式调用不触发异常，返回后并发位已释放（可再次手动 acquire 成功）。
	 */
	@Test
	public void testChatStreamNormalOkAndReleased() {
		ClientFixture f = fixture(1);
		f.downstream.stream("a", "b");
		f.client.chatStream(req("t1"), c -> { });
		// finally 已释放：本应空出的位可立即获取
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
	}

	/**
	 * 下游 chat 抛异常时 finally 仍执行 release：异常向上透传，且并发位被正确归还。
	 */
	@Test
	public void testReleaseRunsWhenDelegateThrows() {
		ClientFixture f = fixture(1);
		f.downstream.throwOn(new IllegalStateException("boom"));
		try {
			f.client.chat(req("t1"));
			fail("expected IllegalStateException from delegate");
		} catch (IllegalStateException ex) {
			assertEquals("boom", ex.getMessage());
		}
		// finally 中 release 已执行：否则位仍被占，这里会拿到 false
		assertTrue("delegate 抛异常后 finally 仍应释放并发位",
			f.enforcer.tryAcquireConcurrency("t1"));
	}

	/**
	 * 下游 chatStream 抛异常时 finally 同样释放并发位。
	 */
	@Test
	public void testChatStreamReleaseWhenDelegateThrows() {
		ClientFixture f = fixture(1);
		f.downstream.throwOn(new IllegalStateException("boom"));
		try {
			f.client.chatStream(req("t1"), c -> { });
			fail("expected IllegalStateException from delegate");
		} catch (IllegalStateException ex) {
			assertEquals("boom", ex.getMessage());
		}
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
	}

	/** 测试夹具：持有执行器、下游假客户端与被测网关客户端。 */
	private static final class ClientFixture {

		/** 预算执行器。 */
		private final BudgetEnforcer enforcer;

		/** 下游假客户端。 */
		private final FakeClient downstream;

		/** 被测租户客户端。 */
		private final TenantAwareGatewayClient client;

		ClientFixture(BudgetEnforcer enforcer, FakeClient downstream,
				TenantAwareGatewayClient client) {
			this.enforcer = enforcer;
			this.downstream = downstream;
			this.client = client;
		}
	}
}
