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

package com.sure.ai.bedrock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link BedrockUtil} 凭证解析测试。
 *
 * @author sureai
 * @since 1.2.0
 */
public class BedrockUtilTest {

	/** 构造含全部凭证的环境映射。 */
	private Map<String, String> baseEnv() {
		Map<String, String> env = new HashMap<>();
		env.put(BedrockUtil.ENV_ACCESS_KEY, "sure-ak");
		env.put(BedrockUtil.ENV_SECRET_KEY, "sure-sk");
		env.put(BedrockUtil.ENV_REGION, "us-west-2");
		return env;
	}

	/** Bedrock 专属变量优先于 AWS 标准变量。 */
	@Test
	public void testPrecedence() {
		Map<String, String> env = baseEnv();
		env.put(BedrockUtil.AWS_ACCESS_KEY_ID, "aws-ak");
		env.put(BedrockUtil.AWS_SECRET_ACCESS_KEY, "aws-sk");
		env.put(BedrockUtil.AWS_REGION, "us-east-1");
		BedrockClient client = BedrockUtil.create(env);
		assertEquals("bedrock", client.name());
		assertNotNull(client);
		client.close();
	}

	/** 回退 AWS 标准变量（含 AWS_DEFAULT_REGION 兜底）。 */
	@Test
	public void testFallbackAwsStandard() {
		Map<String, String> env = new HashMap<>();
		env.put(BedrockUtil.AWS_ACCESS_KEY_ID, "aws-ak");
		env.put(BedrockUtil.AWS_SECRET_ACCESS_KEY, "aws-sk");
		env.put(BedrockUtil.AWS_DEFAULT_REGION, "eu-west-1");
		BedrockClient client = BedrockUtil.create(env);
		assertNotNull(client);
		client.close();
	}

	/** session token 透传。 */
	@Test
	public void testSessionToken() {
		Map<String, String> env = baseEnv();
		env.put(BedrockUtil.ENV_SESSION_TOKEN, "tok");
		BedrockClient client = BedrockUtil.create(env);
		assertNotNull(client);
		client.close();
	}

	/** 缺 Access Key 抛清晰异常。 */
	@Test
	public void testMissingAccessKey() {
		Map<String, String> env = new HashMap<>();
		env.put(BedrockUtil.ENV_SECRET_KEY, "sk");
		env.put(BedrockUtil.ENV_REGION, "us-east-1");
		AiException ex = assertThrows(AiException.class, () -> BedrockUtil.create(env));
		assertTrue(ex.getMessage().contains("Access Key"));
	}

	/** 缺 Secret Key 抛异常。 */
	@Test
	public void testMissingSecretKey() {
		Map<String, String> env = new HashMap<>();
		env.put(BedrockUtil.ENV_ACCESS_KEY, "ak");
		env.put(BedrockUtil.ENV_REGION, "us-east-1");
		assertThrows(AiException.class, () -> BedrockUtil.create(env));
	}

	/** 缺 Region 抛异常。 */
	@Test
	public void testMissingRegion() {
		Map<String, String> env = new HashMap<>();
		env.put(BedrockUtil.ENV_ACCESS_KEY, "ak");
		env.put(BedrockUtil.ENV_SECRET_KEY, "sk");
		assertThrows(AiException.class, () -> BedrockUtil.create(env));
	}

	/** 默认模型透传。 */
	@Test
	public void testDefaultModel() {
		Map<String, String> env = baseEnv();
		env.put(BedrockUtil.ENV_MODEL, BedrockModels.AMAZON_NOVA_PRO);
		BedrockClient client = BedrockUtil.create(env);
		assertNotNull(client);
		client.close();
	}

	/** init(ak,sk,region) 设置单例，client() DCL 返回同一实例。 */
	@Test
	public void testInitSingleton() {
		BedrockUtil.resetClient();
		BedrockUtil.init("ak", "sk", "us-east-1");
		BedrockClient c1 = BedrockUtil.client();
		BedrockClient c2 = BedrockUtil.client();
		assertNotNull(c1);
		assertTrue("client() 应返回同一单例", c1 == c2);
		assertEquals("bedrock", c1.name());
		c1.close();
		BedrockUtil.resetClient();
	}

	/** init(BedrockClient) 注入预构建客户端（用于挂载跨切面能力）。 */
	@Test
	public void testInjectClientSingleton() {
		BedrockUtil.resetClient();
		BedrockClient injected = new BedrockClient("ak", "sk", null, "us-east-1", "m");
		BedrockUtil.init(injected);
		assertTrue(BedrockUtil.client() == injected);
		injected.close();
		BedrockUtil.resetClient();
	}

	/** resetClient 后单例清空：再次 client() 返回的不是旧实例。 */
	@Test
	public void testResetClientClearsSingleton() {
		BedrockUtil.resetClient();
		BedrockUtil.init("ak", "sk", "us-east-1");
		BedrockClient before = BedrockUtil.client();
		BedrockUtil.resetClient();
		// 重新 init 后必须是全新实例（而非复用 reset 前的旧引用）
		BedrockUtil.init("ak2", "sk2", "us-east-1");
		BedrockClient after = BedrockUtil.client();
		assertTrue("resetClient 后应丢弃旧单例", before != after);
		after.close();
		before.close();
		BedrockUtil.resetClient();
	}
}
