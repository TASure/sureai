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
package com.sure.ai.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Map;

import org.junit.Test;

/**
 * {@link VirtualKeyAuth} 单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class VirtualKeyAuthTest {

	/** 注册后的有效 key 返回对应 tenantId。 */
	@Test
	public void testValidKeyReturnsTenant() {
		VirtualKeyAuth auth = new VirtualKeyAuth(Map.of("sk-1", "tenant-a"));
		assertEquals("tenant-a", auth.authenticate("Bearer sk-1"));
		assertEquals(1, auth.size());
	}

	/** 动态注册的 key 也能鉴权通过。 */
	@Test
	public void testRegisterKeyWorks() {
		VirtualKeyAuth auth = new VirtualKeyAuth(Map.of());
		auth.registerKey("sk-2", "tenant-b");
		assertEquals("tenant-b", auth.authenticate("Bearer sk-2"));
	}

	/** 未注册的 key 返回 null。 */
	@Test
	public void testInvalidKeyReturnsNull() {
		VirtualKeyAuth auth = new VirtualKeyAuth(Map.of("sk-1", "tenant-a"));
		assertNull(auth.authenticate("Bearer wrong-key"));
	}

	/** 缺少 Bearer 前缀 / null / 空串 / 错误方案均返回 null。 */
	@Test
	public void testMissingBearerPrefix() {
		VirtualKeyAuth auth = new VirtualKeyAuth(Map.of("sk-1", "tenant-a"));
		assertNull(auth.authenticate(null));
		assertNull(auth.authenticate(""));
		assertNull(auth.authenticate("sk-1"));
		assertNull(auth.authenticate("Basic sk-1"));
		assertNull(auth.authenticate("Bearer "));
	}
}
