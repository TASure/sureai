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

package com.sure.ai.client.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.junit.Test;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * {@link ChatCacheKey} 归一化与 hash 测试。
 *
 * @author sureai
 * @since 1.1.0
 */
public class ChatCacheKeyTest {

	/** 构造一个基础请求。 */
	private static ChatRequest.Builder base() {
		return ChatRequest.builder().model("gpt-4o").messages(ChatMessage.user("hi"));
	}

	/** 相同请求两次 normalize 结果相同。 */
	@Test
	public void testNormalizeDeterministic() {
		ChatRequest r1 = base().build();
		ChatRequest r2 = base().build();
		assertEquals(ChatCacheKey.normalize(r1), ChatCacheKey.normalize(r2));
		assertEquals(ChatCacheKey.of(r1), ChatCacheKey.of(r2));
	}

	/** tools 列表顺序不同但 normalize 结果相同（按 name 排序）。 */
	@Test
	public void testParameterOrderIndependence() {
		ToolSpec a = ToolSpec.of(ToolFunction.of("alpha", "A", "{}"));
		ToolSpec b = ToolSpec.of(ToolFunction.of("beta", "B", "{}"));
		ChatRequest r1 = base().tools(List.of(a, b)).build();
		ChatRequest r2 = base().tools(List.of(b, a)).build();
		assertEquals(ChatCacheKey.normalize(r1), ChatCacheKey.normalize(r2));
		assertEquals(ChatCacheKey.of(r1), ChatCacheKey.of(r2));
	}

	/** model 不同则 key 不同。 */
	@Test
	public void testDifferentModelDifferentKey() {
		ChatRequest r1 = base().model("gpt-4o").build();
		ChatRequest r2 = base().model("gpt-4o-mini").build();
		assertNotEquals(ChatCacheKey.of(r1), ChatCacheKey.of(r2));
	}

	/** messages 不同则 key 不同。 */
	@Test
	public void testDifferentMessagesDifferentKey() {
		ChatRequest r1 = base().messages(ChatMessage.user("hi")).build();
		ChatRequest r2 = base().messages(ChatMessage.user("bye")).build();
		assertNotEquals(ChatCacheKey.of(r1), ChatCacheKey.of(r2));
	}

	/** maxTokens 不参与 hash：不同 maxTokens 但其他相同时 key 相同。 */
	@Test
	public void testMaxTokensIgnored() {
		ChatRequest r1 = base().maxTokens(256).build();
		ChatRequest r2 = base().maxTokens(1024).build();
		assertEquals(ChatCacheKey.of(r1), ChatCacheKey.of(r2));
	}

	/** of() 返回 64 字符 hex（SHA-256）。 */
	@Test
	public void testHashFormat() {
		String key = ChatCacheKey.of(base().build());
		assertNotNull(key);
		assertEquals(64, key.length());
		assert key.matches("[0-9a-f]{64}");
	}
}
