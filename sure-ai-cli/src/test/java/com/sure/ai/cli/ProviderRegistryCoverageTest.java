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
package com.sure.ai.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.client.AiClient;

/**
 * {@link ProviderRegistry} 全覆盖：23 个平台描述的构造 lambda、bedrock 环境变量分支、
 * forName 大小写与 null、私有构造器。
 *
 * <p>零真实网络：仅构造客户端对象（不发起请求）。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class ProviderRegistryCoverageTest {

	/** forName(null) / 未知名 → null；大小写不敏感。 */
	@Test
	public void forNameLookups() {
		assertNull(ProviderRegistry.forName(null));
		assertNull(ProviderRegistry.forName("no-such-provider"));
		assertNotNull(ProviderRegistry.forName("openai"));
		assertNotNull(ProviderRegistry.forName("OPENAI"));
		assertEquals("openai", ProviderRegistry.forName("OpenAI").name());
	}

	/** all() 返回 23 个平台，顺序稳定。 */
	@Test
	public void allHas23Platforms() {
		List<ProviderDescriptor> all = ProviderRegistry.all();
		assertEquals(23, all.size());
		assertEquals("openai", all.get(0).name());
		assertEquals("spark", all.get(22).name());
	}

	/** 逐个平台调用构造 lambda（带 baseUrl），覆盖每个平台的 client 构造分支。 */
	@Test
	public void buildEveryProviderClient() {
		Map<String, String> envMap = new HashMap<>();
		envMap.put("AWS_ACCESS_KEY_ID", "ak-test");
		envMap.put("AWS_SECRET_ACCESS_KEY", "sk-test");
		envMap.put("AWS_REGION", "us-east-1");
		Environment env = envMap::get;

		for (ProviderDescriptor d : ProviderRegistry.all()) {
			AiClient client = d.constructor().build("dummy-key", "http://localhost:1", env);
			assertNotNull("client for " + d.name() + " must not be null", client);
			assertNotNull(client.name());
		}
	}

	/** bedrock 缺环境变量 → 抛 CliException（EXIT_USAGE）。 */
	@Test
	public void bedrockMissingEnvThrows() {
		ProviderDescriptor bedrock = ProviderRegistry.forName("bedrock");
		assertNotNull(bedrock);
		Environment emptyEnv = name -> null;
		try {
			bedrock.constructor().build("k", null, emptyEnv);
			fail("expected CliException");
		} catch (CliException e) {
			assertEquals(CliException.EXIT_USAGE, e.exitCode());
			assertTrue(e.getMessage().contains("AWS_ACCESS_KEY_ID"));
		}
	}

	/** 本地无 Key 平台 ollama：以占位 dummy key 构造成功。 */
	@Test
	public void ollamaBuildsWithoutKey() {
		ProviderDescriptor ollama = ProviderRegistry.forName("ollama");
		assertNotNull(ollama);
		AiClient client = ollama.constructor().build("dummy", null, name -> null);
		assertNotNull(client);
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void privateConstructorThrows() throws Exception {
		Constructor<ProviderRegistry> ctor = ProviderRegistry.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("expected AssertionError");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof AssertionError);
		}
	}
}
