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

package com.sure.ai.quarkus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;

import org.junit.Test;

import com.sure.ai.baidu.BaiduClient;
import com.sure.ai.bedrock.BedrockClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.minimax.MiniMaxClient;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.qwen.QwenClient;

/**
 * {@link SureAiClientFactory} 的纯单元测试（不启动 Quarkus 容器，实例化客户端不触发网络）。
 *
 * <p>覆盖：ClientSpec → AiConfig 字段映射、平台选择（api-key 存在与否）、
 * 各平台 Client 反射实例化、百度 secretKey 透传、Bedrock 特殊构造。</p>
 */
public class SureAiClientFactoryTest {

	/** 从客户端实例上取私有 config 字段（与 spring-boot-starter 测试同法）。 */
	private static AiConfig configOf(Object client) throws Exception {
		Class<?> c = client.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField("config");
				f.setAccessible(true);
				return (AiConfig) f.get(client);
			}
			catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException("config");
	}

	private static ClientSpec baseSpec(String apiKey) {
		ClientSpec s = new ClientSpec();
		s.setApiKey(apiKey);
		return s;
	}

	@Test
	public void mapsApiKeyIntoAiConfig() {
		AiConfig cfg = SureAiClientFactory.toAiConfig(baseSpec("sk-123"));
		assertEquals("sk-123", cfg.apiKey());
	}

	@Test
	public void mapsBaseUrlWhenPresent() {
		ClientSpec s = baseSpec("sk");
		s.setBaseUrl("https://proxy.example.com/v1");
		AiConfig cfg = SureAiClientFactory.toAiConfig(s);
		assertEquals("https://proxy.example.com/v1", cfg.baseUrl());
	}

	@Test
	public void leavesBaseUrlNullWhenBlank() {
		ClientSpec s = baseSpec("sk");
		s.setBaseUrl("  ");
		AiConfig cfg = SureAiClientFactory.toAiConfig(s);
		assertEquals(null, cfg.baseUrl());
	}

	@Test
	public void mapsTimeoutsAndCacheTtlFromMillis() {
		ClientSpec s = baseSpec("sk");
		s.setTimeoutMillis(30_000);
		s.setConnectTimeoutMillis(2_000);
		s.setCacheTtlMillis(300_000);
		AiConfig cfg = SureAiClientFactory.toAiConfig(s);
		assertEquals(Duration.ofSeconds(30), cfg.timeout());
		assertEquals(Duration.ofSeconds(2), cfg.connectTimeout());
		assertEquals(Duration.ofMinutes(5), cfg.cacheTtl());
	}

	@Test
	public void mapsProxyOrganizationRetriesAndRateLimit() {
		ClientSpec s = baseSpec("sk");
		s.setProxy("127.0.0.1:7890");
		s.setOrganization("org-1");
		s.setMaxRetries(5);
		s.setRateLimitQps(10.5);
		AiConfig cfg = SureAiClientFactory.toAiConfig(s);
		assertEquals("127.0.0.1:7890", cfg.proxy());
		assertEquals("org-1", cfg.organization());
		assertEquals(5, cfg.maxRetries());
		assertEquals(10.5, cfg.rateLimitQps(), 0.0001);
	}

	@Test
	public void mapsExtraHeaders() {
		ClientSpec s = baseSpec("sk");
		s.getExtraHeaders().put("X-App", "demo");
		s.getExtraHeaders().put("X-Trace", "t-1");
		AiConfig cfg = SureAiClientFactory.toAiConfig(s);
		assertEquals("demo", cfg.extraHeaders().get("X-App"));
		assertEquals("t-1", cfg.extraHeaders().get("X-Trace"));
	}

	@Test
	public void defaultValuesWhenOnlyApiKeyGiven() {
		AiConfig cfg = SureAiClientFactory.toAiConfig(baseSpec("sk"));
		assertEquals(Duration.ofSeconds(60), cfg.timeout());
		assertEquals(Duration.ofSeconds(10), cfg.connectTimeout());
		assertEquals(2, cfg.maxRetries());
		assertEquals(0.0, cfg.rateLimitQps(), 0.0001);
	}

	@Test
	public void baiduSecretKeyMappedToExtraHeader() {
		ClientSpec s = baseSpec("api-xxx");
		s.setSecretKey("secret-yyy");
		AiConfig cfg = SureAiClientFactory.toAiConfig(s);
		assertEquals("secret-yyy", cfg.extraHeaders().get(BaiduClient.SECRET_KEY_HEADER));
	}

	@Test
	public void createsOpenAiClient() throws Exception {
		Object client = SureAiClientFactory.newClient(
			SureAiClientFactory.PLATFORM_CLIENT_CLASSES.get("openai"), baseSpec("sk-openai"));
		assertTrue(client instanceof OpenAiClient);
		assertEquals("sk-openai", configOf(client).apiKey());
	}

	@Test
	public void createsNewGenerationPlatformClient() throws Exception {
		// v1.9.0 新增平台之一：MiniMax，同样走 AiConfig 构造器
		Object client = SureAiClientFactory.newClient(
			SureAiClientFactory.PLATFORM_CLIENT_CLASSES.get("minimax"), baseSpec("sk-mm"));
		assertTrue(client instanceof MiniMaxClient);
		assertEquals("sk-mm", configOf(client).apiKey());
	}

	@Test
	public void createsQwenClientWithFullConfig() throws Exception {
		ClientSpec s = baseSpec("sk-qwen");
		s.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
		s.setTimeoutMillis(15_000);
		Object client = SureAiClientFactory.newClient(
			SureAiClientFactory.PLATFORM_CLIENT_CLASSES.get("qwen"), s);
		assertTrue(client instanceof QwenClient);
		AiConfig cfg = configOf(client);
		assertEquals("sk-qwen", cfg.apiKey());
		assertEquals(Duration.ofSeconds(15), cfg.timeout());
	}

	@Test
	public void platformTableCoversAllAiConfigPlatforms() {
		// 22 个 AiConfig 族平台（Bedrock 单独走 newBedrock）
		assertEquals(22, SureAiClientFactory.PLATFORM_CLIENT_CLASSES.size());
		assertTrue(SureAiClientFactory.PLATFORM_CLIENT_CLASSES.containsKey("spark"));
		assertTrue(SureAiClientFactory.PLATFORM_CLIENT_CLASSES.containsKey("hunyuan"));
		assertTrue(SureAiClientFactory.PLATFORM_CLIENT_CLASSES.containsKey("siliconflow"));
	}

	@Test
	public void createsBedrockClient() {
		BedrockSpec spec = new BedrockSpec();
		spec.setAccessKey("AKIA-TEST");
		spec.setSecretKey("secret-test");
		spec.setRegion("us-east-1");
		spec.setModel("anthropic.claude-3-5-sonnet-20240620-v1:0");
		BedrockClient client = SureAiClientFactory.newBedrock(spec);
		assertNotNull(client);
	}

	@Test
	public void recorderDelegatesToFactory() throws Exception {
		// recorder.createClient 应返回与 factory.newClient 等价的实例（RuntimeValue 包裹）
		SureAiRecorder recorder = new SureAiRecorder();
		Object client = recorder.createClient(
			SureAiClientFactory.PLATFORM_CLIENT_CLASSES.get("openai"), baseSpec("sk-rec")).getValue();
		assertTrue(client instanceof OpenAiClient);
		assertEquals("sk-rec", configOf(client).apiKey());
	}

	@Test
	public void recorderBedrockDelegate() {
		SureAiRecorder recorder = new SureAiRecorder();
		BedrockSpec spec = new BedrockSpec();
		spec.setAccessKey("AKIA-TEST");
		spec.setSecretKey("secret-test");
		spec.setRegion("us-east-1");
		Object client = recorder.createBedrock(spec).getValue();
		assertSame(BedrockClient.class, client.getClass());
	}
}
