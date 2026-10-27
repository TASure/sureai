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

package com.sure.ai.framework.advisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.sure.ai.framework.cache.SemanticCache;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

import org.junit.Test;

/**
 * {@link SemanticCacheAdvisor} 命中短路 / 未命中回填测试（零真实网络）。
 *
 * @author sureai
 * @since 2.5.0
 */
public class SemanticCacheAdvisorTest {

	/** 可编程的假语义缓存。 */
	private static final class FakeCache implements SemanticCache {
		ChatResponse hit;
		ChatResponse stored;
		String storedQuery;

		@Override
		public ChatResponse get(String query) {
			return this.hit;
		}

		@Override
		public void put(String query, ChatResponse response, long ttlMillis) {
			this.storedQuery = query;
			this.stored = response;
		}

		@Override
		public void remove(String query) {
		}

		@Override
		public void clear() {
		}
	}

	private static AdvisorContext ctx(String userText) {
		ChatRequest req = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user(userText))).build();
		return new AdvisorContext(req, null, null);
	}

	@Test
	public void hitShortCircuitsClientAndStillRunsAfter() {
		FakeCache cache = new FakeCache();
		ChatResponse cached = ScriptedClient.text("缓存答案");
		cache.hit = cached;

		ScriptedClient client = new ScriptedClient().then(ScriptedClient.text("真实答案"));
		AdvisorChain chain = new AdvisorChain(List.of(new SemanticCacheAdvisor(cache)),
			c -> client.chat(c.rebuildRequest()));

		ChatResponse resp = chain.execute(ctx("怎么退烧"));

		assertSame(cached, resp);
		assertEquals("命中短路后不应调用模型", 0, client.callCount());
	}

	@Test
	public void missCallsClientAndBackfills() {
		FakeCache cache = new FakeCache();
		ScriptedClient client = new ScriptedClient().then(ScriptedClient.text("真实答案"));
		AdvisorChain chain = new AdvisorChain(List.of(new SemanticCacheAdvisor(cache)),
			c -> client.chat(c.rebuildRequest()));

		ChatResponse resp = chain.execute(ctx("怎么退烧"));

		assertEquals("真实答案", resp.firstText());
		assertEquals(1, client.callCount());
		assertEquals("怎么退烧", cache.storedQuery);
		assertSame(resp, cache.stored);
	}

	@Test
	public void secondCallHitsBackfilledCache() {
		FakeCache cache = new FakeCache();
		ScriptedClient client = new ScriptedClient().then(ScriptedClient.text("真实答案"));
		SemanticCacheAdvisor advisor = new SemanticCacheAdvisor(cache);

		// 第一次：miss → 调用并回填
		AdvisorChain chain = new AdvisorChain(List.of(advisor),
			c -> client.chat(c.rebuildRequest()));
		chain.execute(ctx("问题甲"));
		assertEquals(1, client.callCount());

		// 模拟回填后第二次语义命中
		cache.hit = cache.stored;
		chain.execute(ctx("问题甲（同义改写）"));
		assertEquals("第二次应命中缓存，不再调用模型", 1, client.callCount());
	}

	@Test
	public void blankQuerySkipsCache() {
		FakeCache cache = new FakeCache();
		ScriptedClient client = new ScriptedClient().then(ScriptedClient.text("ok"));
		AdvisorChain chain = new AdvisorChain(List.of(new SemanticCacheAdvisor(cache)),
			c -> client.chat(c.rebuildRequest()));
		// 无 user 文本
		ChatRequest req = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.assistant("sys"))).build();
		chain.execute(new AdvisorContext(req, null, null));
		assertEquals(1, client.callCount());
		assertTrue(cache.stored == null);
	}
}
