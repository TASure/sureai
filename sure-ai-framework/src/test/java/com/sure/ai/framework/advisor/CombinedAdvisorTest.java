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
import static org.junit.Assert.assertNotNull;

import java.util.List;

import com.sure.ai.framework.FrameworkUtil;
import com.sure.ai.framework.annotation.AiService;
import com.sure.ai.framework.annotation.Tool;
import com.sure.ai.framework.annotation.UserMessage;
import com.sure.ai.framework.cache.SemanticCache;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ToolCall;

import org.junit.Test;

/**
 * 四件套组合：语义缓存（miss）→ 日志 → 工具循环 → 结构化校验 的端到端零网络测试。
 *
 * @author sureai
 * @since 2.5.0
 */
public class CombinedAdvisorTest {

	/** 目标 record。 */
	public record Product(String name, int stock) {
	}

	@AiService(model = "m")
	interface Catalog {

		@UserMessage("{q}")
		Product lookup(String q);

		@Tool(description = "查库存")
		default int stockOf(String item) {
			return 42;
		}
	}

	/** 永远 miss、只记录写入的假缓存。 */
	private static final class NeverHitCache implements SemanticCache {
		ChatResponse stored;

		@Override
		public ChatResponse get(String query) {
			return null;
		}

		@Override
		public void put(String query, ChatResponse response, long ttlMillis) {
			this.stored = response;
		}

		@Override
		public void remove(String query) {
		}

		@Override
		public void clear() {
		}
	}

	@Test
	public void fourAdvisorsComposeEndToEnd() {
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.toolCalls(
				List.of(ToolCall.of("1", "stockOf", "{\"item\":\"书\"}"))))
			.then(ScriptedClient.text("{\"name\":\"书\",\"stock\":42}"));
		NeverHitCache cache = new NeverHitCache();

		List<Advisor> advisors = List.of(
			new SemanticCacheAdvisor(cache),
			new LoggingAdvisor(),
			new ToolCallingAdvisor(),
			new StructuredOutputValidationAdvisor());

		Catalog ai = FrameworkUtil.builder(Catalog.class, client)
			.advisors(advisors).build();

		Product p = ai.lookup("查一下书的库存");
		assertEquals("书", p.name());
		assertEquals(42, p.stock());
		assertEquals("模型应被调用两次（工具轮 + 最终轮）", 2, client.callCount());
		assertNotNull("最终响应应回填语义缓存", cache.stored);
	}
}
