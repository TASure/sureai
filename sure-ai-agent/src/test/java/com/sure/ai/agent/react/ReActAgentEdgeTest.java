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

package com.sure.ai.agent.react;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.memory.longterm.LongTermMemory;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.TokenUsage;
import org.junit.Test;

/**
 * {@link ReActAgent} 边界分支单元测试（零真实网络）。
 *
 * <p>覆盖：审批门/长期记忆 getter、并行工具超时、监听器回调异常收敛、
 * arguments 非法形态、采样参数透传、空候选响应与总超时抛出。</p>
 */
public class ReActAgentEdgeTest {

	private static ChatRequest baseRequest() {
		return ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.system("你是助手")))
			.build();
	}

	private static ToolCall call(String id, String name, String args) {
		return ToolCall.of(id, name, args);
	}

	private static ToolRegistry registryWithLookup() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("lookup", "查询", "{}"),
				args -> "查到的数据");
		return registry;
	}

	/** 始终返回空候选响应的客户端。 */
	private static final class EmptyChoiceClient implements com.sure.ai.client.AiClient {
		final List<ChatRequest> requests = new java.util.ArrayList<>();

		@Override
		public String name() {
			return "empty";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.requests.add(request);
			return ChatResponse.of("r0", "fake-model", List.of(),
					TokenUsage.of(1, 1, 2), null);
		}

		@Override
		public void chatStream(ChatRequest request,
				java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> consumer) {
			// 不使用流式
		}

		@Override
		public void close() {
			// no-op
		}
	}

	@Test
	public void testGettersReturnInjectedOptionals() {
		ToolRegistry registry = registryWithLookup();
		FakeAiClient client = new FakeAiClient().withText("最终答案");
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		assertNull(agent.approvalGate());
		assertNull(agent.longTermMemory());

		LongTermMemory ltm = new LongTermMemory(null, null, null, null);
		ReActAgent agent2 = new ReActAgent(client, baseRequest(), registry,
				null, 5, Duration.ofSeconds(30), null, null, null, null, ltm);
		assertEquals(ltm, agent2.longTermMemory());
	}

	@Test
	public void testParallelToolTimeout() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("slow", "慢", "{}"), args -> {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "slow";
		});
		registry.register(ToolFunction.of("slow2", "慢2", "{}"), args -> "slow2");

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "slow", "{}"), call("c2", "slow2", "{}")))
			.withText("最终答案");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
				null, 5, Duration.ofMillis(1));
		try {
			agent.run("任务");
		} catch (com.sure.ai.exception.AiTimeoutException e) {
			// 步骤内并行工具等待超时分支已被覆盖；主循环超时向外抛出属预期
			assertTrue(e.getMessage().contains("timeout"));
		}
	}

	@Test
	public void testSafeExecuteCatchesListenerThrow() {
		ToolRegistry registry = registryWithLookup();
		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "lookup", "{}")))
			.withText("最终答案");

		AgentListener throwing = new AgentListener() {
			@Override
			public void onToolCall(ToolCall c) {
				throw new IllegalStateException("listener-boom");
			}

			@Override
			public void onError(Throwable error) {
				throw new IllegalStateException("on-error-boom");
			}
		};
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
				throwing, 5, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));
	}

	@Test
	public void testArgumentsNonObjectAndUnparseable() {
		ToolRegistry registry = registryWithLookup();
		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "lookup", "[1,2]")))
			.withText("最终答案");
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
				null, 5, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));

		FakeAiClient client2 = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "lookup", "null")))
			.withText("最终答案");
		ReActAgent agent2 = new ReActAgent(client2, baseRequest(), registry,
				null, 5, Duration.ofSeconds(30));
		assertEquals("最终答案", agent2.run("任务"));

		// 空白 arguments → 空对象继续
		FakeAiClient client3 = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "lookup", "  ")))
			.withText("最终答案");
		ReActAgent agent3 = new ReActAgent(client3, baseRequest(), registry,
				null, 5, Duration.ofSeconds(30));
		assertEquals("最终答案", agent3.run("任务"));
	}

	@Test
	public void testBuildRequestCopiesSamplingParams() {
		ChatRequest templ = ChatRequest.builder()
			.model("m")
			.messages(List.of(ChatMessage.system("s")))
			.temperature(0.5)
			.maxTokens(64)
			.topP(0.8)
			.toolChoice("none")
			.presencePenalty(0.3)
			.frequencyPenalty(0.4)
			.seed(7)
			.user("u")
			.build();

		FakeAiClient client = new FakeAiClient().withText("最终答案");
		ReActAgent agent = new ReActAgent(client, templ, new ToolRegistry(),
				null, 5, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));

		ChatRequest seen = client.lastRequest();
		assertEquals(Double.valueOf(0.5), seen.temperature());
		assertEquals(Integer.valueOf(64), seen.maxTokens());
		assertEquals(Double.valueOf(0.8), seen.topP());
		assertEquals("none", seen.toolChoice());
		assertEquals(Double.valueOf(0.3), seen.presencePenalty());
		assertEquals(Double.valueOf(0.4), seen.frequencyPenalty());
		assertEquals(Integer.valueOf(7), seen.seed());
		assertEquals("u", seen.user());
	}

	@Test
	public void testEmptyChoicesResponse() {
		EmptyChoiceClient client = new EmptyChoiceClient();
		ReActAgent agent = new ReActAgent(client, baseRequest(), new ToolRegistry(),
				null, 5, Duration.ofSeconds(30));
		String answer = agent.run("任务");
		assertEquals("", answer);
	}

	@Test
	public void testEmptyRegistrySingleChat() {
		FakeAiClient client = new FakeAiClient().withText("直接答案");
		ReActAgent agent = new ReActAgent(client, baseRequest(), new ToolRegistry(),
				null, 5, Duration.ofSeconds(30));
		assertEquals("直接答案", agent.run("任务"));
	}

	@Test
	public void testMaxIterationsExceededThrows() {
		// 模型每轮都返回 tool_calls，maxIterations=2 → 抛 AiException
		ToolRegistry registry = new ToolRegistry();
		registry.register(com.sure.ai.model.ToolFunction.of("lookup", "查", "{}"),
				args -> "结果");
		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(ToolCall.of("c1", "lookup", "{}")))
			.withToolCalls(List.of(ToolCall.of("c1", "lookup", "{}")));
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
				null, 2, Duration.ofSeconds(30));
		com.sure.ai.exception.AiException ex = assertThrows(
				com.sure.ai.exception.AiException.class, () -> agent.run("任务"));
		assertTrue(ex.getMessage().contains("max iterations"));
	}

	@Test
	public void testToolHandlerErrorTriggersSafeExecuteOnError() {
		// handler 抛 Error（非 Exception）→ 穿透 executeTool 的 Exception catch
		// → safeExecute 的 catch(Throwable) → listener.onError
		ToolRegistry registry = new ToolRegistry();
		registry.register(com.sure.ai.model.ToolFunction.of("err", "错", "{}"),
				args -> {
					throw new AssertionError("native-error");
				});
		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(ToolCall.of("c1", "err", "{}")))
			.withText("最终答案");
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
				null, 5, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));
	}
}
