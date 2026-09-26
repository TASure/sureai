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
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.ApiKeyProvider;
import com.sure.ai.client.RoundRobinApiKeyProvider;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiRateLimitException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

/**
 * {@link KeyRotatingClientDecorator} 单元测试（零网络）。
 *
 * @author sureai
 * @since 1.6.0
 */
public class KeyRotatingClientDecoratorTest {

	/** 构造一个最简请求。 */
	private static ChatRequest req() {
		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
	}

	/**
	 * 第一次 401：装饰器换 key 重建客户端重试成功。
	 */
	@Test
	public void testRotationOn401() {
		FakeClient k1 = new FakeClient("k1").throwOn(new AiAuthException("bad key", "{}"));
		FakeClient k2 = new FakeClient("k2");
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", k1);
		map.put("k2", k2);
		Function<String, AiClient> factory = map::get;

		ApiKeyProvider provider = new RoundRobinApiKeyProvider(List.of("k1", "k2"));
		KeyRotatingClientDecorator decorator = new KeyRotatingClientDecorator(provider, factory);

		String text = decorator.chat(req()).firstText();
		assertEquals("k2:ok", text);
		assertEquals(1, k1.chatCalls);
		assertEquals(1, k2.chatCalls);
	}

	/**
	 * 第一次 429：换 key 重试成功。
	 */
	@Test
	public void testRotationOn429() {
		FakeClient k1 = new FakeClient("k1").throwOn(new AiRateLimitException("rate", "{}", 1));
		FakeClient k2 = new FakeClient("k2");
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", k1);
		map.put("k2", k2);

		ApiKeyProvider provider = new RoundRobinApiKeyProvider(List.of("k1", "k2"));
		KeyRotatingClientDecorator decorator = new KeyRotatingClientDecorator(provider, map::get);

		assertEquals("k2:ok", decorator.chat(req()).firstText());
		assertEquals(1, k1.chatCalls);
		assertEquals(1, k2.chatCalls);
	}

	/**
	 * 5xx 不换 key：直接抛出，下一个 key 不被调用。
	 */
	@Test
	public void testNoRotationOn5xx() {
		FakeClient k1 = new FakeClient("k1").throwOn(new AiApiException(500, "server", "boom", "{}"));
		FakeClient k2 = new FakeClient("k2");
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", k1);
		map.put("k2", k2);

		ApiKeyProvider provider = new RoundRobinApiKeyProvider(List.of("k1", "k2"));
		KeyRotatingClientDecorator decorator = new KeyRotatingClientDecorator(provider, map::get);

		try {
			decorator.chat(req());
			fail("expected AiApiException");
		} catch (AiApiException ex) {
			assertEquals(500, ex.getHttpStatus());
		}
		assertEquals(1, k1.chatCalls);
		assertEquals(0, k2.chatCalls);
	}

	/**
	 * 流式调用在 401 时也换 key 重试成功。
	 */
	@Test
	public void testStreamRotationOn401() {
		FakeClient k1 = new FakeClient("k1").throwOn(new AiAuthException("bad key", "{}"));
		FakeClient k2 = new FakeClient("k2").stream("hello", "world");
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", k1);
		map.put("k2", k2);

		ApiKeyProvider provider = new RoundRobinApiKeyProvider(List.of("k1", "k2"));
		KeyRotatingClientDecorator decorator = new KeyRotatingClientDecorator(provider, map::get);

		StringBuilder out = new StringBuilder();
		decorator.chatStream(req(), chunk -> out.append(chunk.deltaText()));
		assertEquals("helloworld", out.toString());
	}

	/**
	 * 所有 key 都 401：抛 AiException，含全部 suppressed 异常。
	 */
	@Test
	public void testAllKeysExhausted() {
		AiAuthException e1 = new AiAuthException("k1 bad", "{}");
		AiAuthException e2 = new AiAuthException("k2 bad", "{}");
		AiAuthException e3 = new AiAuthException("k3 bad", "{}");
		FakeClient c1 = new FakeClient("c1").throwOn(e1);
		FakeClient c2 = new FakeClient("c2").throwOn(e2);
		FakeClient c3 = new FakeClient("c3").throwOn(e3);
		Map<String, AiClient> map = new HashMap<>();
		map.put("k1", c1);
		map.put("k2", c2);
		map.put("k3", c3);

		ApiKeyProvider provider = new RoundRobinApiKeyProvider(List.of("k1", "k2", "k3"));
		KeyRotatingClientDecorator decorator = new KeyRotatingClientDecorator(provider, map::get);

		try {
			decorator.chat(req());
			fail("expected AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("3"));
			assertEquals(3, ex.getSuppressed().length);
			assertSame(e1, ex.getSuppressed()[0]);
		}
		assertEquals(1, c1.chatCalls);
		assertEquals(1, c2.chatCalls);
		assertEquals(1, c3.chatCalls);
		// k1/k2/k3 均已被标记坏：currentKey 在全部冷却期内仍会轮询（这里只验证不抛 NPE）
		assertFalse(provider.allKeys().isEmpty());
		assertEquals(3, provider.size());
	}
}
