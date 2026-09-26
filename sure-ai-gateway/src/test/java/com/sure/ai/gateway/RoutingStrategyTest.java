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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.sure.ai.client.Capability;
import com.sure.ai.cost.PriceCatalog;

/**
 * 六种路由策略的单元测试。
 *
 * @author sureai
 * @since 1.6.0
 */
public class RoutingStrategyTest {

	/** 构造一个测试候选。 */
	private static ClientCandidate cand(String platform, double weight, String defaultModel) {
		return new ClientCandidate(new FakeClient(platform), platform, "default",
				Set.of(Capability.CHAT, Capability.CHAT_STREAM, Capability.EMBED),
				weight, defaultModel);
	}

	/** 显式平台命中对应客户端；未指定时走兜底。 */
	@Test
	public void explicitRouting() {
		ClientCandidate openai = cand("openai", 1.0, null);
		ClientCandidate deepseek = cand("deepseek", 1.0, null);
		ExplicitRoutingStrategy strategy = new ExplicitRoutingStrategy(new RoundRobinStrategy());

		Map<String, Object> extra = new HashMap<>();
		extra.put(RequestContext.EXTRA_PLATFORM, "deepseek");
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "gpt-4o", extra, null);

		assertSame(deepseek, strategy.select(ctx, List.of(openai, deepseek)));

		RequestContext free = new RequestContext("chat", Capability.CHAT, "gpt-4o", Map.of(), null);
		ClientCandidate picked = strategy.select(free, List.of(openai, deepseek));
		assertTrue(picked == openai || picked == deepseek);
	}

	/** 能力路由只保留声明了所需能力的客户端。 */
	@Test
	public void capabilityRouting() {
		ClientCandidate chatOnly = new ClientCandidate(new FakeClient("chat"), "chat", "default",
				Set.of(Capability.CHAT), 1.0, null);
		ClientCandidate withEmbed = cand("embed", 1.0, null);
		CapabilityRoutingStrategy strategy = new CapabilityRoutingStrategy(new RoundRobinStrategy());

		RequestContext embedCtx = new RequestContext("embed", Capability.EMBED, "m", Map.of(), null);
		ClientCandidate picked = strategy.select(embedCtx, List.of(chatOnly, withEmbed));
		assertSame(withEmbed, picked);

		RequestContext chatCtx = new RequestContext("chat", Capability.CHAT, "m", Map.of(), null);
		// CHAT 两者都声明，不应返回 null
		assertTrue(strategy.select(chatCtx, List.of(chatOnly, withEmbed)) != null);
	}

	/** 轮询：10 次选择在两个客户端间均匀交替。 */
	@Test
	public void roundRobin() {
		ClientCandidate a = cand("a", 1.0, null);
		ClientCandidate b = cand("b", 1.0, null);
		RoundRobinStrategy strategy = new RoundRobinStrategy();
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "m", Map.of(), null);

		int countA = 0;
		for (int i = 0; i < 10; i++) {
			if (strategy.select(ctx, List.of(a, b)) == a) {
				countA++;
			}
		}
		assertEquals(5, countA);
	}

	/** 加权：权重 1:3 时高权重被选中约 75%（误差 <10%）。 */
	@Test
	public void weighted() {
		ClientCandidate low = cand("low", 1.0, null);
		ClientCandidate high = cand("high", 3.0, null);
		WeightedRoutingStrategy strategy = new WeightedRoutingStrategy();
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "m", Map.of(), null);

		int highHits = 0;
		int total = 2000;
		for (int i = 0; i < total; i++) {
			if (strategy.select(ctx, List.of(low, high)) == high) {
				highHits++;
			}
		}
		double ratio = (double) highHits / total;
		assertTrue("expected ~0.75 but was " + ratio, ratio > 0.65 && ratio < 0.85);
	}

	/** 最低延迟：记录不同延迟后选中平均延迟最低者。 */
	@Test
	public void lowestLatency() {
		LatencyTracker tracker = new LatencyTracker();
		ClientCandidate fast = cand("fast", 1.0, null);
		ClientCandidate slow = cand("slow", 1.0, null);
		tracker.record("fast", "default", 50L);
		tracker.record("slow", "default", 500L);

		LowestLatencyStrategy strategy = new LowestLatencyStrategy(tracker);
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "m", Map.of(), null);
		assertSame(fast, strategy.select(ctx, List.of(fast, slow)));
	}

	/** 最低成本：PriceCatalog 中 gpt-4o-mini 比 gpt-4o 便宜，选中 mini。 */
	@Test
	public void lowestCost() {
		PriceCatalog catalog = PriceCatalog.defaults();
		ClientCandidate expensive = new ClientCandidate(new FakeClient("exp"), "exp", "default",
				Set.of(Capability.CHAT), 1.0, "gpt-4o");
		ClientCandidate cheap = new ClientCandidate(new FakeClient("cheap"), "cheap", "default",
				Set.of(Capability.CHAT), 1.0, "gpt-4o-mini");
		LowestCostStrategy strategy = new LowestCostStrategy(catalog);
		RequestContext ctx = new RequestContext("chat", Capability.CHAT, "gpt-4o", Map.of(), null);

		assertSame(cheap, strategy.select(ctx, List.of(expensive, cheap)));
	}
}
