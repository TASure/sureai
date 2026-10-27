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
 * {@link SSRFGuard} 单元测试。
 *
 * <p>所有用例均使用字面量 IP 地址或 localhost，不依赖外部 DNS 解析，
 * 可离线运行。</p>
 */
public class SSRFGuardTest {

	private static void assertBlocked(String url) {
		String reason = SSRFGuard.check(URI.create(url));
		assertNotNull("expected SSRF block for " + url + " but got null (allowed)", reason);
		assertTrue("reason should not be empty for " + url, !reason.isEmpty());
	}

	private static void assertAllowed(String url) {
		String reason = SSRFGuard.check(URI.create(url));
		assertNull("expected " + url + " to be allowed, but was blocked: " + reason, reason);
	}

	// --- 回环地址 ---

	@Test
	public void testBlocksIPv4Loopback() {
		assertBlocked("http://127.0.0.1/admin");
	}

	@Test
	public void testBlocksIPv6Loopback() {
		assertBlocked("http://[::1]/admin");
	}

	@Test
	public void testBlocksLocalhost() {
		// localhost 通常解析为 127.0.0.1 / ::1
		assertBlocked("http://localhost/");
	}

	// --- 链路本地（云元数据服务等） ---

	@Test
	public void testBlocksLinkLocalMetadata() {
		// AWS/GCP 元数据服务
		assertBlocked("http://169.254.169.254/latest/meta-data/");
	}

	@Test
	public void testBlocksIPv6LinkLocal() {
		assertBlocked("http://[fe80::1]/");
	}

	// --- 私有段 ---

	@Test
	public void testBlocksPrivate10() {
		assertBlocked("http://10.0.0.1/");
	}

	@Test
	public void testBlocksPrivate172_16() {
		assertBlocked("http://172.16.0.1/");
	}

	@Test
	public void testBlocksPrivate192_168() {
		assertBlocked("http://192.168.1.1/");
	}

	// --- 任意本地地址 ---

	@Test
	public void testBlocksAnyLocalIPv4() {
		assertBlocked("http://0.0.0.0/");
	}

	// --- 公网地址放行 ---

	@Test
	public void testAllowsPublicIPv4() {
		// 8.8.8.8 是 Google DNS，公网地址，字面量解析无需 DNS
		assertAllowed("http://8.8.8.8/");
	}

	@Test
	public void testAllowsPublicIPv4Second() {
		// 1.1.1.1 Cloudflare DNS
		assertAllowed("http://1.1.1.1/");
	}

	// --- 无 host ---

	@Test
	public void testMissingHost() {
		String reason = SSRFGuard.check(URI.create("http:///"));
		assertNotNull(reason);
		assertTrue(reason.contains("host"));
	}
}
