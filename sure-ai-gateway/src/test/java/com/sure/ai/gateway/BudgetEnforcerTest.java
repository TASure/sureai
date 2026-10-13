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

	/**
	 * maxConcurrency=2 的租户前两次 tryAcquireConcurrency 放行、第三次已满返回 false。
	 */
	@Test
	public void testConcurrencyLimitAllowsUpToMax() {
		Fixture f = fixture(TenantConfig.builder().maxConcurrency(2).build());
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		assertFalse("并发已满应返回 false", f.enforcer.tryAcquireConcurrency("t1"));
	}

	/**
	 * releaseConcurrency 对称释放后，被占满的并发位可再次 acquire。
	 */
	@Test
	public void testConcurrencyReleaseThenAcquireAgain() {
		Fixture f = fixture(TenantConfig.builder().maxConcurrency(1).build());
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		assertFalse(f.enforcer.tryAcquireConcurrency("t1"));
		f.enforcer.releaseConcurrency("t1");
		assertTrue("释放后应可再次获取", f.enforcer.tryAcquireConcurrency("t1"));
	}

	/**
	 * 未配置租户的 tryAcquireConcurrency 恒 true、releaseConcurrency 为 no-op。
	 */
	@Test
	public void testUnknownTenantConcurrencyAlwaysTrue() {
		Fixture f = fixture(TenantConfig.builder().maxConcurrency(2).build());
		assertTrue(f.enforcer.tryAcquireConcurrency("ghost"));
		assertTrue(f.enforcer.tryAcquireConcurrency("ghost"));
		f.enforcer.releaseConcurrency("ghost");
	}

	/**
	 * maxConcurrency=0（不限）的租户不建闸门，任意多次 acquire 恒 true。
	 */
	@Test
	public void testUnlimitedConcurrencyTenantAlwaysTrue() {
		Fixture f = fixture(TenantConfig.builder().maxConcurrency(0).build());
		for (int i = 0; i < 5; i++) {
			assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		}
		f.enforcer.releaseConcurrency("t1");
	}

	/**
	 * tenantId 为 null / 空白时并发检查直接放行，不抛异常。
	 */
	@Test
	public void testBlankTenantConcurrencyUnrestricted() {
		Fixture f = fixture(TenantConfig.builder().maxConcurrency(1).build());
		assertTrue(f.enforcer.tryAcquireConcurrency(null));
		assertTrue(f.enforcer.tryAcquireConcurrency("  "));
		f.enforcer.releaseConcurrency(null);
		f.enforcer.releaseConcurrency("  ");
	}

	/**
	 * 并发闸门与 checkBeforeCall 的 QPS/预算检查互不干扰：占满并发位不影响预算检查通过。
	 */
	@Test
	public void testConcurrencyIndependentOfBudgetCheck() {
		Fixture f = fixture(TenantConfig.builder().maxConcurrency(1).build());
		// 占满唯一并发位
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		assertFalse(f.enforcer.tryAcquireConcurrency("t1"));
		// checkBeforeCall 仍按既有语义正常放行（预算/QPS 未受限）
		f.enforcer.checkBeforeCall("t1", 0.0);
		f.enforcer.recordAfterCall("t1", "m", TokenUsage.of(1, 1, 2), 0.0);
		assertEquals(1, f.enforcer.currentUsage("t1").requestsInWindow());
	}

	/**
	 * 同租户改配 maxConcurrency 后新闸门按新上限重建生效（旧闸门被替换）。
	 */
	@Test
	public void testConcurrencyRebuiltOnConfigChange() {
		Fixture f = fixture(TenantConfig.builder().maxConcurrency(1).build());
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		assertFalse(f.enforcer.tryAcquireConcurrency("t1"));
		// 扩到 3：下次准入按新上限重建闸门
		f.mgr.configureTenant("t1", TenantConfig.builder().maxConcurrency(3).build());
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		assertTrue(f.enforcer.tryAcquireConcurrency("t1"));
		assertEquals(3, f.enforcer.concurrencyLimit("t1"));
	}

	/**
	 * concurrencyLimit 返回当前配置上限；未配置租户返回 0。
	 */
	@Test
	public void testConcurrencyLimitQuery() {
		Fixture f = fixture(TenantConfig.builder().maxConcurrency(4).build());
		assertEquals(4, f.enforcer.concurrencyLimit("t1"));
		assertEquals(0, f.enforcer.concurrencyLimit("ghost"));
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
