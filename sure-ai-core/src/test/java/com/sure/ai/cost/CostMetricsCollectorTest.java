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

package com.sure.ai.cost;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/**
 * {@link CostMetricsCollector} 集成测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class CostMetricsCollectorTest {

	/** 清理 ThreadLocal 避免测试间污染。 */
	@After
	public void tearDown() {
		CostMetricsCollector.clearCurrentTenantId();
	}

	/** CostMetricsCollector 的 onTokenUsage 自动计算并记录成本。 */
	@Test
	public void testCostMetricsCollectorIntegration() {
		PriceCatalog catalog = PriceCatalog.defaults();
		CostAggregator agg = new CostAggregator();
		CostMetricsCollector collector = new CostMetricsCollector(catalog, agg);

		// 设置租户
		CostMetricsCollector.setCurrentTenantId("svc-gateway");
		// 模拟 onTokenUsage 回调：gpt-4o, 1000 prompt, 500 completion, 1500 total
		collector.onTokenUsage("gpt-4o", 1000L, 500L, 1500L);

		// 验证 aggregator 记录了成本
		CostSummary s = agg.tenantSummary("svc-gateway");
		assertEquals(1L, s.totalCalls());
		assertEquals(1000L, s.totalPromptTokens());
		assertEquals(500L, s.totalCompletionTokens());
		// gpt-4o: (1000*0.005 + 500*0.020)/1000 = (5.0+10.0)/1000 = 0.015
		assertEquals(0.015, s.totalCost(), 0.0001);
		assertEquals(1500L, s.tokensByModel().get("gpt-4o").longValue());
	}

	/** 未设置租户时归入 default。 */
	@Test
	public void testCostMetricsCollectorDefaultTenant() {
		PriceCatalog catalog = PriceCatalog.defaults();
		CostAggregator agg = new CostAggregator();
		CostMetricsCollector collector = new CostMetricsCollector(catalog, agg);

		collector.onTokenUsage("o4-mini", 500L, 200L, 700L);

		CostSummary s = agg.tenantSummary(CostMetricsCollector.DEFAULT_TENANT);
		assertEquals(1L, s.totalCalls());
		// o4-mini: (500*0.0011 + 200*0.0044)/1000 = (0.55+0.88)/1000 = 0.00143
		assertEquals(0.00143, s.totalCost(), 0.0001);
	}

	/** 多租户隔离：不同 ThreadLocal 租户分别记录。 */
	@Test
	public void testCostMetricsCollectorTenantIsolation() {
		PriceCatalog catalog = PriceCatalog.defaults();
		CostAggregator agg = new CostAggregator();
		CostMetricsCollector collector = new CostMetricsCollector(catalog, agg);

		CostMetricsCollector.setCurrentTenantId("alice");
		collector.onTokenUsage("gpt-4o", 1000L, 500L, 1500L);

		CostMetricsCollector.clearCurrentTenantId();
		CostMetricsCollector.setCurrentTenantId("bob");
		collector.onTokenUsage("gpt-4o-mini", 100L, 50L, 150L);

		assertEquals(1L, agg.tenantSummary("alice").totalCalls());
		assertEquals(1L, agg.tenantSummary("bob").totalCalls());
		assertEquals(0L, agg.tenantSummary("charlie").totalCalls());
	}
}
