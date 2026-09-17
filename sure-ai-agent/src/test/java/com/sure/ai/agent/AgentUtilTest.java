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

package com.sure.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.List;

import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolFunction;
import org.junit.After;
import org.junit.Test;

/**
 * {@link AgentUtil} 单元测试。
 */
public class AgentUtilTest {

	@After
	public void tearDown() {
		AgentUtil.resetRegistry();
	}

	@Test
	public void testRegistrySingleton() {
		ToolRegistry r1 = AgentUtil.registry();
		ToolRegistry r2 = AgentUtil.registry();
		assertSame(r1, r2);
	}

	@Test
	public void testRegisterTool() {
		AgentUtil.registerTool(ToolFunction.of("ping", "pong", "{}"), args -> "pong");
		assertEquals(1, AgentUtil.registry().size());
	}

	@Test
	public void testResetRegistry() {
		AgentUtil.registerTool(ToolFunction.of("a", "a", "{}"), args -> "1");
		AgentUtil.resetRegistry();
		assertEquals(0, AgentUtil.registry().size());
	}

	@Test
	public void testReactFactory() {
		FakeClient client = new FakeClient();
		ChatRequest base = ChatRequest.builder()
			.model("m")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
		ReActAgent a1 = AgentUtil.react(client, base);
		assertNotNull(a1);
		ReActAgent a2 = AgentUtil.react(client, base, new AgentListener() {
		});
		assertNotNull(a2);
	}

	/** 最小 Fake 客户端（注册中心为空，直接返回文本）。 */
	private static final class FakeClient implements com.sure.ai.client.AiClient {
		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			return ChatResponse.of("r", "m",
					List.of(Choice.of(0, ChatMessage.assistant("ok"), "stop")),
					TokenUsage.of(1, 1, 2), null);
		}

		@Override
		public void chatStream(ChatRequest request,
				java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}
}
