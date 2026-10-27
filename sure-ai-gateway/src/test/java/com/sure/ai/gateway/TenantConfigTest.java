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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link TenantConfig} 并发上限新组件的单元测试。
 *
 * @author sureai
 * @since 2.7.0
 */
public class TenantConfigTest {

	/**
	 * builder 未设置与 unlimited() 的 maxConcurrency 默认 0（不限），limitedConcurrency() 返回 false。
	 */
	@Test
	public void testDefaultMaxConcurrencyZero() {
		TenantConfig built = TenantConfig.builder().build();
		assertEquals(0, built.maxConcurrency());
		assertFalse(built.limitedConcurrency());

		TenantConfig unlimited = TenantConfig.unlimited();
		assertEquals(0, unlimited.maxConcurrency());
		assertFalse(unlimited.limitedConcurrency());
	}

	/**
	 * builder.maxConcurrency(3) 生效：取值 3 且 limitedConcurrency() 返回 true。
	 */
	@Test
	public void testBuilderMaxConcurrencyTakesEffect() {
		TenantConfig cfg = TenantConfig.builder()
			.maxCostPerPeriod(1.0)
			.rateLimitQps(5)
			.maxConcurrency(3)
			.build();
		assertEquals(3, cfg.maxConcurrency());
		assertTrue(cfg.limitedConcurrency());
		// 其余字段不被新组件影响
		assertEquals(1.0, cfg.maxCostPerPeriod(), 1e-9);
		assertEquals(5, cfg.rateLimitQps());
	}

	/**
	 * maxConcurrency 为负数时紧凑构造器抛 IllegalArgumentException（builder 与 5 参构造器同一路径）。
	 */
	@Test
	public void testNegativeMaxConcurrencyRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> TenantConfig.builder().maxConcurrency(-1).build());
		assertThrows(IllegalArgumentException.class,
			() -> new TenantConfig(0, 0L, 0, null, -1));
	}
}
