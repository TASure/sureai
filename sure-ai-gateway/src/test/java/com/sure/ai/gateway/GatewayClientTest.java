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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * {@link GatewayClient} 端到端测试（零网络）。
 *
 * @author sureai
 * @since 1.6.0
 */
public class GatewayClientTest {

	private static ChatRequest req() {
		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
	}

	/** name() 返回 gateway。 */
	@Test
	public void nameIsGateway() {
		GatewayClient gw = new GatewayClient(new ClientRegistry());
		assertEquals("gateway", gw.name());
	}

	/** chat 路由成功：返回正确响应。 */
	@Test
	public void chatRoutesAndReturns() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient openai = new FakeClient("openai");
		registry.register("openai", openai);
		GatewayClient gw = new GatewayClient(registry, new RoundRobinStrategy(),
				FailoverConfig.defaults());

		ChatResponse resp = gw.chat(req());
		assertEquals("openai:ok", resp.firstText());
		assertEquals(1, openai.chatCalls);
	}

	/** chatStream 正确透传分片。 */
	@Test
	public void chatStreamPassesThroughChunks() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient client = new FakeClient("openai").stream("Hello", ", ", "world");
		registry.register("openai", client);
		GatewayClient gw = new GatewayClient(registry, new RoundRobinStrategy(),
				FailoverConfig.defaults());

		List<String> collected = new ArrayList<>();
		gw.chatStream(req(), (ChatStreamChunk chunk) -> collected.add(chunk.deltaText()));

		assertEquals(List.of("Hello", ", ", "world"), collected);
	}

	/** close() 关闭所有注册的 client。 */
	@Test
	public void closeClosesAllClients() {
		ClientRegistry registry = new ClientRegistry();
		FakeClient a = new FakeClient("a");
		FakeClient b = new FakeClient("b");
		registry.register("openai", "a", a);
		registry.register("deepseek", "b", b);
		GatewayClient gw = new GatewayClient(registry);

		gw.close();
		assertTrue(a.closed);
		assertTrue(b.closed);
	}
}
