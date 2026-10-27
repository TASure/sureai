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

package com.sure.ai.agent.orchestrator;

import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Test;

import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;

/**
 * {@link AgentOrchestrator} 测试：拆分并行、异常隔离、直接子任务、自定义聚合器。
 */
public class AgentOrchestratorTest {

	private java.util.concurrent.ExecutorService executor;

	private AgentOrchestrator newOrchestrator(
			java.util.function.Function<String, ReActAgent> factory) {
		this.executor = java.util.concurrent.Executors.newFixedThreadPool(2);
		return AgentOrchestrator.builder(factory)
			.executor(this.executor)
			.timeout(Duration.ofSeconds(10))
			.build();
	}

	@After
	public void tearDown() {
		if (this.executor != null) {
			this.executor.shutdownNow();
		}
	}

	private static ChatRequest baseRequest() {
		return ChatRequest.builder()
			.model("fake-model")
			.messages(ChatMessage.system("sys"))
			.build();
	}

	@Test
	public void testSplitAndExecute() {
		AgentOrchestrator orch = newOrchestrator(sub ->
			new ReActAgent(new EchoClient(), baseRequest(), new ToolRegistry()));
		String result = orch.execute("para one\n\npara two\n\npara three");
		assertTrue(result, result.contains("para one"));
		assertTrue(result, result.contains("para two"));
		assertTrue(result, result.contains("para three"));
	}

	@Test
	public void testExceptionIsolation() {
		AgentOrchestrator orch = newOrchestrator(sub -> {
			AiClient client = sub.contains("bad") ? new FailingClient() : new EchoClient();
			return new ReActAgent(client, baseRequest(), new ToolRegistry());
		});
		String result = orch.execute(List.of("good-one", "bad-task", "good-two"));
		assertTrue(result, result.contains("[ERROR:"));
		assertTrue(result, result.contains("good-one"));
		assertTrue(result, result.contains("good-two"));
	}

	@Test
	public void testDirectSubtasks() {
		AgentOrchestrator orch = newOrchestrator(sub ->
			new ReActAgent(new EchoClient(), baseRequest(), new ToolRegistry()));
		String result = orch.execute(List.of("AAA", "BBB"));
		assertTrue(result, result.contains("AAA"));
		assertTrue(result, result.contains("BBB"));
	}

	@Test
	public void testCustomAggregator() {
		this.executor = java.util.concurrent.Executors.newFixedThreadPool(2);
		AgentOrchestrator orch = AgentOrchestrator.builder(sub ->
				new ReActAgent(new EchoClient(), baseRequest(), new ToolRegistry()))
			.executor(this.executor)
			.aggregator(results -> String.join("|", results))
			.build();
		String result = orch.execute(List.of("x", "y"));
		assertTrue(result, result.equals("x|y"));
	}

	/** 回显最后一条 user 消息的假客户端，零网络。 */
	static final class EchoClient implements AiClient {
		@Override
		public String name() {
			return "echo";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			String last = "";
			for (ChatMessage m : request.messages()) {
				if (m.role() == Role.USER && m.content() != null) {
					last = m.content();
				}
			}
			return ChatResponse.of("id", "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(last), "stop")),
				TokenUsage.of(1, 1, 2), null);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}

	/** chat 直接抛异常的假客户端。 */
	static final class FailingClient implements AiClient {
		@Override
		public String name() {
			return "failing";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			throw new RuntimeException("boom");
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}
}
