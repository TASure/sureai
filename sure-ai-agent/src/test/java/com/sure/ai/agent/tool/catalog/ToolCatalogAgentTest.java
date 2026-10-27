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

package com.sure.ai.agent.tool.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;

/**
 * 市场导出到 ToolRegistry 后，ReActAgent 经脚本化假客户端可调用（零真实网络）。
 */
public class ToolCatalogAgentTest {

	/** 脚本化假客户端：按序返回预设响应并记录请求。 */
	static final class ScriptedClient implements AiClient {
		private final List<ChatResponse> responses = new ArrayList<>();
		private final List<ChatRequest> requests = new ArrayList<>();
		private int idx;

		ScriptedClient withToolCalls(List<ToolCall> calls) {
			this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(calls), "tool_calls")),
				TokenUsage.of(1, 1, 2), null));
			return this;
		}

		ScriptedClient withText(String text) {
			this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(text), "stop")),
				TokenUsage.of(1, 1, 2), null));
			return this;
		}

		List<ChatRequest> requests() {
			return this.requests;
		}

		@Override
		public String name() {
			return "scripted";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.requests.add(request);
			return this.responses.get(Math.min(this.idx++, this.responses.size() - 1));
		}

		@Override
		public void chatStream(ChatRequest request,
				java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> consumer) {
			// 测试不使用流式
		}

		@Override
		public void close() {
			// no-op
		}
	}

	private static ChatRequest baseRequest() {
		return ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.system("你是助手")))
			.build();
	}

	@Test
	public void testExportedToolCallableByAgent() throws Exception {
		ToolCatalog catalog = new ToolCatalog();
		catalog.register(
			com.sure.ai.model.ToolFunction.of("market_calc", "市场导出的样例工具", "{}"),
			args -> "MARKET_RESULT",
			ToolMetadata.builder("market_calc").addTag("math").build());

		ToolRegistry registry = new ToolRegistry();
		int exported = catalog.exportTo(registry);
		assertEquals(1, exported);

		ScriptedClient client = new ScriptedClient()
			.withToolCalls(List.of(ToolCall.of("c1", "market_calc", "{}")))
			.withText("市场工具已调用完毕。");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("调用市场工具");
		assertEquals("市场工具已调用完毕。", answer);
		// 第一轮请求携带导出的工具声明
		assertTrue(client.requests().get(0).tools().stream()
			.anyMatch(t -> t.function().name().equals("market_calc")));
		// 第二轮历史包含工具回灌结果
		String history = client.requests().get(1).messages().stream()
			.map(m -> m.content() == null ? "" : m.content())
			.reduce("", String::concat);
		assertTrue(history.contains("MARKET_RESULT"));
	}
}
