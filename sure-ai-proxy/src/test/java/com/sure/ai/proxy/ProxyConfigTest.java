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
import static org.junit.Assert.assertTrue;

import java.util.Properties;

import org.junit.Test;

/**
 * {@link ProxyConfig} 单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class ProxyConfigTest {

	/** 从 Properties 加载端口/默认模型/模型列表/虚拟密钥。 */
	@Test
	public void testLoadFromProperties() {
		Properties p = new Properties();
		p.setProperty("proxy.port", "9090");
		p.setProperty("proxy.default.model", "foo-model");
		p.setProperty("proxy.models", "gpt-4o, gpt-3.5-turbo");
		p.setProperty("proxy.key.sk-tenant1-key", "tenant1");
		p.setProperty("proxy.key.sk-tenant2-key", "tenant2");

		ProxyConfig config = ProxyConfig.fromProperties(p);
		assertEquals(9090, config.port());
		assertEquals("foo-model", config.defaultModel());
		assertEquals(2, config.exposedModels().size());
		assertEquals("gpt-4o", config.exposedModels().get(0));
		assertEquals("gpt-3.5-turbo", config.exposedModels().get(1));
		assertEquals("tenant1", config.keyToTenant().get("sk-tenant1-key"));
		assertEquals("tenant2", config.keyToTenant().get("sk-tenant2-key"));
	}

	/** 未配置时使用默认值（端口 8080 / 默认模型 / 仅暴露默认模型 / 无密钥）。 */
	@Test
	public void testDefaults() {
		ProxyConfig config = ProxyConfig.defaults();
		assertEquals(ProxyConfig.DEFAULT_PORT, config.port());
		assertEquals(ProxyConfig.DEFAULT_MODEL, config.defaultModel());
		assertEquals(1, config.exposedModels().size());
		assertEquals(ProxyConfig.DEFAULT_MODEL, config.exposedModels().get(0));
		assertTrue(config.keyToTenant().isEmpty());
	}

	/** 非法端口回退默认；未配置 models 时回退默认模型。 */
	@Test
	public void testInvalidPortFallback() {
		Properties p = new Properties();
		p.setProperty("proxy.port", "not-a-port");
		ProxyConfig config = ProxyConfig.fromProperties(p);
		assertEquals(ProxyConfig.DEFAULT_PORT, config.port());
		assertEquals(1, config.exposedModels().size());
		assertEquals(ProxyConfig.DEFAULT_MODEL, config.exposedModels().get(0));
	}
}
