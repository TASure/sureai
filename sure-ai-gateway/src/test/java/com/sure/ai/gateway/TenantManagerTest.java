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
import static org.junit.Assert.assertNull;

import java.time.Duration;

import org.junit.Test;

/**
 * {@link TenantManager} 单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class TenantManagerTest {

	/**
	 * registerVirtualKey 后 resolveTenant 返回正确租户。
	 */
	@Test
	public void testRegisterAndResolve() {
		TenantManager mgr = new TenantManager();
		mgr.registerVirtualKey("vk-1", "tenant-a");
		assertEquals("tenant-a", mgr.resolveTenant("vk-1"));
	}

	/**
	 * 未注册的 key 返回 null。
	 */
	@Test
	public void testUnknownKeyReturnsNull() {
		TenantManager mgr = new TenantManager();
		mgr.registerVirtualKey("vk-1", "tenant-a");
		assertNull(mgr.resolveTenant("not-registered"));
		assertNull(mgr.resolveTenant(null));
	}

	/**
	 * remove 后 resolve 返回 null。
	 */
	@Test
	public void testRemoveKey() {
		TenantManager mgr = new TenantManager();
		mgr.registerVirtualKey("vk-1", "tenant-a");
		assertEquals("tenant-a", mgr.resolveTenant("vk-1"));
		mgr.removeVirtualKey("vk-1");
		assertNull(mgr.resolveTenant("vk-1"));
	}

	/**
	 * configureTenant 后 configFor 返回正确配置。
	 */
	@Test
	public void testConfigureTenant() {
		TenantManager mgr = new TenantManager();
		TenantConfig cfg = TenantConfig.builder()
			.maxCostPerPeriod(2.5)
			.maxTokensPerPeriod(10_000)
			.rateLimitQps(10)
			.budgetPeriod(Duration.ofHours(6))
			.build();
		mgr.configureTenant("tenant-a", cfg);

		TenantConfig back = mgr.configFor("tenant-a");
		assertEquals(2.5, back.maxCostPerPeriod(), 1e-9);
		assertEquals(10_000L, back.maxTokensPerPeriod());
		assertEquals(10, back.rateLimitQps());
		assertEquals(Duration.ofHours(6), back.budgetPeriod());
		// 未配置租户返回 null
		assertNull(mgr.configFor("ghost"));
	}
}
