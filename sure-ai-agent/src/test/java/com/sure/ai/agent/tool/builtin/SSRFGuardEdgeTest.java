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

package com.sure.ai.agent.tool.builtin;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Test;

/**
 * {@link SSRFGuard} 边界补充测试。
 *
 * <p>全部使用字面量 IP 或文档保留前缀，离线可运行；
 * 覆盖多播、IPv4-mapped IPv6 内嵌内网地址、公网 IPv6 放行与不可解析主机。</p>
 */
public class SSRFGuardEdgeTest {

	@Test
	public void testBlocksMulticast() {
		String reason = SSRFGuard.check(URI.create("http://224.0.0.1/"));
		assertNotNull(reason);
		assertTrue(reason.contains("multicast"));
	}

	@Test
	public void testBlocksIPv4MappedIPv6Private() {
		// ::ffff:10.0.0.1 内嵌私网地址；JDK 自带方法不识别映射后的内网 IPv4，
		// 需由 extractEmbeddedIPv4 提取后判定为 site-local
		String reason = SSRFGuard.check(URI.create("http://[::ffff:a00:1]/"));
		assertNotNull(reason);
		// 内嵌私网地址被拦截（JDK 自带或内嵌提取判定均可）
		assertTrue(reason, reason.contains("10.0.0.1"));
	}

	@Test
	public void testAllowsPublicIPv6() {
		// 文档前缀 2001:db8::/32，公网保留地址
		String reason = SSRFGuard.check(URI.create("http://[2001:db8::1]/"));
		assertNull(reason);
	}

	@Test
	public void testIPv6NotMappedPrefixFallsThrough() {
		// ::c0a8:1 前 10 字节为 0 但 11/12 字节非 0xffff → 不做内嵌提取，放行
		String reason = SSRFGuard.check(URI.create("http://[::c0a8:1]/"));
		assertNull(reason);
	}

	@Test
	public void testMappedIPv6EmbeddedLinkLocal() {
		// ::ffff:169.254.0.1：外层 IPv6 不被 JDK 判定为内网，内嵌 169.254.x 为链路本地
		String reason = SSRFGuard.check(URI.create("http://[::ffff:a9fe:1]/"));
		assertNotNull(reason);
		assertTrue(reason, reason.contains("IPv4-mapped") || reason.contains("169.254"));
	}

	@Test
	public void testUnresolvableHost() {
		// RFC 2606 保留后缀，本地解析器立即 NXDOMAIN，不触达外部网络
		String reason = SSRFGuard.check(URI.create("http://nonexistent.invalid/"));
		assertNotNull(reason);
		assertTrue(reason.contains("cannot resolve"));
	}
}
