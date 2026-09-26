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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.Capability;

/**
 * {@link ClientRegistry} 单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class ClientRegistryTest {

	/** 注册后 all()/byPlatform() 返回正确。 */
	@Test
	public void registerAndQuery() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient openai = new FakeClient("openai-c1");
		FakeClient deepseek = new FakeClient("deepseek-c1");
		registry.register("openai", openai);
		registry.register("deepseek", deepseek);

		assertEquals(2, registry.all().size());
		assertEquals(1, registry.byPlatform("openai").size());
		assertSame(openai, registry.byName("openai", ClientRegistry.DEFAULT_INSTANCE));
		assertTrue(registry.byPlatform("missing").isEmpty());
	}

	/** 同平台多实例：byPlatform 返回全部实例。 */
	@Test
	public void samePlatformMultipleInstances() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient c1 = new FakeClient("openai-us");
		FakeClient c2 = new FakeClient("openai-eu");
		registry.register("openai", "us", c1);
		registry.register("openai", "eu", c2);

		List<AiClient> list = registry.byPlatform("openai");
		assertEquals(2, list.size());
		assertSame(c1, registry.byName("openai", "us"));
		assertSame(c2, registry.byName("openai", "eu"));
	}

	/** byCapability 按注册时声明的能力过滤。 */
	@Test
	public void byCapabilityFilter() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient chatOnly = new FakeClient("chat-only");
		FakeClient withEmbed = new FakeClient("with-embed");
		registry.register("openai", chatOnly, Set.of(Capability.CHAT, Capability.CHAT_STREAM));
		registry.register("gemini", withEmbed,
				Set.of(Capability.CHAT, Capability.EMBED, Capability.IMAGE));

		List<AiClient> embed = registry.byCapability(Capability.EMBED);
		assertEquals(1, embed.size());
		assertSame(withEmbed, embed.get(0));

		assertEquals(2, registry.byCapability(Capability.CHAT).size());
	}

	/** markUnhealthy 后不健康，冷却期内 healthyCandidates 摘除；冷却后恢复。 */
	@Test
	public void healthMarkAndRecover() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient c1 = new FakeClient("c1");
		registry.register("openai", "inst", c1);

		assertTrue(registry.isHealthy("openai", "inst"));
		assertEquals(1, registry.healthyCandidates().size());

		registry.markUnhealthy("openai", "inst", 60_000L);
		assertFalse(registry.isHealthy("openai", "inst"));
		assertTrue(registry.healthyCandidates().isEmpty());

		registry.markHealthy("openai", "inst");
		assertTrue(registry.isHealthy("openai", "inst"));
		assertEquals(1, registry.healthyCandidates().size());
	}

	/** remove 后查询不再返回。 */
	@Test
	public void removeInstance() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient c1 = new FakeClient("c1");
		FakeClient c2 = new FakeClient("c2");
		registry.register("openai", "us", c1);
		registry.register("openai", "eu", c2);

		registry.remove("openai", "us");
		assertEquals(1, registry.byPlatform("openai").size());
		assertTrue(registry.byName("openai", "us") == null);

		registry.removeAll("openai");
		assertTrue(registry.byPlatform("openai").isEmpty());
	}
}
