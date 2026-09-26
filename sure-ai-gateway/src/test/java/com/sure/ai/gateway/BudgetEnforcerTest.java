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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.sure.ai.cost.CostAggregator;
import com.sure.ai.exception.AiBudgetExceededException;
import com.sure.ai.model.TokenUsage;

/**
 * {@link BudgetEnforcer} 单元测试（零网络、可注入伪时钟）。
 *
 * @author sureai
 * @since 1.6.0
 */
public class BudgetEnforcerTest {

	/** 构造一个已配置好 t1 的执行器。 */
	private static Fixture fixture(TenantConfig cfg) {
		TenantManager mgr = new TenantManager();
		mgr.configureTenant("t1", cfg);
		CostAggregator agg = new CostAggregator();
		return new Fixture(mgr, agg);
	}

	/**
	 * 用量未超限时 checkBeforeCall 不抛异常。
	 */
	@Test
	public void testUnderBudgetAllowed() {
		Fixture f = fixture(TenantConfig.builder()
			.maxCostPerPeriod(1.0).maxTokensPerPeriod(10_000).build());
		f.enforcer.checkBeforeCall("t1", 0.5);
		f.enforcer.recordAfterCall("t1", "gpt-4o", TokenUsage.of(100, 50, 150), 0.5);

		TenantUsage u = f.enforcer.currentUsage("t1");
		assertEquals(0.5, u.costUsed(), 1e-9);
		assertEquals(150L, u.tokensUsed());
		assertEquals(1, u.requestsInWindow());
		assertEquals(1.0, u.costLimit(), 1e-9);
	}

	/**
	 * 用量超限时抛 AiBudgetExceededException，消息含租户名和用量/限额。
	 */
	@Test
	public void testOverBudgetRejected() {
		Fixture f = fixture(TenantConfig.builder().maxCostPerPeriod(1.0).build());
		f.enforcer.recordAfterCall("t1", "gpt-4o", TokenUsage.of(0, 0, 0), 2.0);

		try {
			f.enforcer.checkBeforeCall("t1", 0.0);
			fail("expected AiBudgetExceededException");
		} catch (AiBudgetExceededException ex) {
			assertEquals("t1", ex.tenantId());
			assertTrue(ex.getMessage().contains("t1"));
			assertTrue(ex.getMessage().contains("used"));
			assertTrue(ex.getMessage().contains("$2.0000"));
		}
	}

	/**
	 * recordAfterCall 后 currentUsage 反映新增成本/token。
	 */
	@Test
	public void testRecordAfterCallUpdatesUsage() {
		Fixture f = fixture(TenantConfig.unlimited());
		f.enforcer.recordAfterCall("t1", "gpt-4o", TokenUsage.of(200, 100, 300), 0.1);
		f.enforcer.recordAfterCall("t1", "gpt-4o", TokenUsage.of(100, 50, 150), 0.2);

		TenantUsage u = f.enforcer.currentUsage("t1");
		assertEquals(0.3, u.costUsed(), 1e-9);
		assertEquals(450L, u.tokensUsed());
		assertEquals(2, u.requestsInWindow());
		// 同时写入共享 CostAggregator
		assertEquals(2, f.agg.tenantSummary("t1").totalCalls());
		assertEquals(450L, f.agg.tenantSummary("t1").totalPromptTokens()
				+ f.agg.tenantSummary("t1").totalCompletionTokens());
	}

	/**
	 * 超过 QPS 限制时被拒绝。
	 */
	@Test
	public void testRateLimit() {
		Fixture f = fixture(TenantConfig.builder().rateLimitQps(2).build());
		// 桶容量=2：前两次放行，第三次拒绝
		f.enforcer.checkBeforeCall("t1", 0.0);
		f.enforcer.checkBeforeCall("t1", 0.0);
		try {
			f.enforcer.checkBeforeCall("t1", 0.0);
			fail("expected AiBudgetExceededException (rate limit)");
		} catch (AiBudgetExceededException ex) {
			assertTrue(ex.getMessage().contains("rate limit"));
		}
	}

	/**
	 * 周期过后用量清零（可注入时钟）。
	 */
	@Test
	public void testBudgetPeriodReset() {
		AtomicLong now = new AtomicLong(0L);
		TenantManager mgr = new TenantManager();
		mgr.configureTenant("t1", TenantConfig.builder()
			.maxCostPerPeriod(1.0)
			.budgetPeriod(Duration.ofMillis(1_000L))
			.build());
		CostAggregator agg = new CostAggregator();
		BudgetEnforcer enforcer = new BudgetEnforcer(mgr, agg, now::get);

		enforcer.recordAfterCall("t1", "gpt-4o", TokenUsage.of(10, 10, 20), 0.8);
		assertEquals(0.8, enforcer.currentUsage("t1").costUsed(), 1e-9);

		// 周期结束（now=1000 >= period=1000）：下次检查触发清零
		now.set(1_000L);
		enforcer.checkBeforeCall("t1", 0.0);
		TenantUsage u = enforcer.currentUsage("t1");
		assertEquals(0.0, u.costUsed(), 1e-9);
		assertEquals(0L, u.tokensUsed());
		assertEquals(0, u.requestsInWindow());
	}

	/**
	 * maxCost=0/maxTokens=0 的租户不受限。
	 */
	@Test
	public void testUnlimitedTenant() {
		Fixture f = fixture(TenantConfig.unlimited());
		// 巨大预估成本 / 巨大 token 都不拦
		f.enforcer.checkBeforeCall("t1", 999_999.0);
		f.enforcer.recordAfterCall("t1", "gpt-4o", TokenUsage.of(1_000_000, 1_000_000, 2_000_000),
				9_999.0);
		f.enforcer.checkBeforeCall("t1", 999_999.0);

		TenantUsage u = f.enforcer.currentUsage("t1");
		assertEquals(0.0, u.costLimit(), 1e-9);
		assertEquals(0L, u.tokenLimit());
		assertEquals(0, u.qpsLimit());
	}

	/** 测试夹具。 */
	private static final class Fixture {
		private final TenantManager mgr;
		private final CostAggregator agg;
		private final BudgetEnforcer enforcer;

		Fixture(TenantManager mgr, CostAggregator agg) {
			this.mgr = mgr;
			this.agg = agg;
			this.enforcer = new BudgetEnforcer(mgr, agg);
		}
	}
}
