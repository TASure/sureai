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
}
