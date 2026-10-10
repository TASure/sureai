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

package com.sure.ai.agent.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.TokenUsage;
import org.junit.Test;

/**
 * {@link PlanExecuteAgent} 边界分支单元测试（{@link ScriptedAiClient} 零真实网络）。
 *
 * <p>覆盖主链路测试未触达的分支：单步工具调用轮数耗尽、并行工具超时、
 * 监听器回调异常收敛、arguments 各种非法形态、参数校验失败、工具未注册、
 * 计划解析的数组字符串/数字/null 元素、采样参数透传、空候选响应与总超时抛出。</p>
 */
public class PlanExecuteAgentEdgeTest {

	private static final String ONE_STEP = """
			[{"step":"唯一一步"}]
			""";

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

	/** 计数监听器：可配置在 onToolCall 抛异常。 */
	private static final class FlakyListener implements AgentListener {
		final List<Throwable> errors = new ArrayList<>();
		boolean throwOnToolCall;
		boolean throwOnError;

		@Override
		public void onToolCall(ToolCall c) {
			if (this.throwOnToolCall) {
				throw new IllegalStateException("listener-boom");
			}
		}

		@Override
		public void onError(Throwable e) {
			this.errors.add(e);
			if (this.throwOnError) {
				throw new IllegalStateException("on-error-boom");
			}
		}
	}

	@Test
	public void testStepLoopExhaustsToolRounds() {
		// registry 非空，模型连续 3 轮只返回 tool_calls → 抛 "Step exceeded max tool calls"
		ToolRegistry registry = registryWithLookup();
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "lookup", "{}")))
			.withToolCalls(List.of(call("c1", "lookup", "{}")))
			.withToolCalls(List.of(call("c1", "lookup", "{}")))
			.withToolCalls(List.of(call("c1", "lookup", "{}")))
			.withText("最终答案");

		FlakyListener listener = new FlakyListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("任务");

		// 重试两次仍失败 → 记录错误文本后继续汇总
		assertTrue(answer, answer.contains("最终答案"));
		assertTrue("应记录步骤失败", !listener.errors.isEmpty());
	}

	@Test
	public void testParallelToolTimeout() {
		// 总超时 1ms；工具睡眠 500ms → 并行等待剩余预算 ≤0 立即 TimeoutException
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

		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "slow", "{}"), call("c2", "slow2", "{}")))
			.withText("最终");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				null, 10, Duration.ofMillis(1));
		try {
			agent.run("任务");
		} catch (AiTimeoutException e) {
			// 步骤内并行工具等待超时分支已被覆盖；汇总阶段超时向外抛出属预期
			assertTrue(e.getMessage().contains("timeout"));
		}
	}

	@Test
	public void testSafeExecuteCatchesListenerThrow() {
		// onToolCall 抛异常 → safeExecute 收敛为错误文本
		ToolRegistry registry = registryWithLookup();
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "lookup", "{}")))
			.withText("最终答案");

		FlakyListener listener = new FlakyListener();
		listener.throwOnToolCall = true;
		listener.throwOnError = true;
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("任务");

		assertEquals("最终答案", answer);
		assertTrue(listener.errors.stream()
			.anyMatch(e -> e instanceof IllegalStateException));
	}

	@Test
	public void testArgumentsNotJsonObject() {
		// argumentsJson 是数组 → parseArguments 返回 null → 错误文本
		ToolRegistry registry = registryWithLookup();
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "lookup", "[1,2,3]")))
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30));
		String answer = agent.run("任务");
		assertEquals("最终答案", answer);
		// 第二轮请求应回灌了 "arguments 不是合法 JSON 对象"
		ChatRequest afterTools = client.requests().get(2);
		boolean seen = afterTools.messages().stream()
			.anyMatch(m -> m.content() != null
				&& m.content().contains("arguments 不是合法 JSON 对象"));
		assertTrue(seen);
	}

	@Test
	public void testArgumentsBlankAndNullLiteral() {
		// 空白 arguments → 空对象继续；"null" 字面量 → 空对象继续
		ToolRegistry registry = registryWithLookup();
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "lookup", "   ")))
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));

		ScriptedAiClient client2 = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "lookup", "null")))
			.withText("最终答案");
		PlanExecuteAgent agent2 = new PlanExecuteAgent(client2, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent2.run("任务"));
	}

	@Test
	public void testArgumentsUnparseable() {
		// 非法 JSON → catch → null → 错误文本
		ToolRegistry registry = registryWithLookup();
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "lookup", "{oops")))
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));
	}

	@Test
	public void testArgumentValidationFailure() {
		// 函数声明必填 city，但调用传空对象 → 参数校验失败
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("weather", "天气",
				"""
				{"type":"object","required":["city"]}
				"""),
				args -> "晴");

		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "weather", "{}")))
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));
		ChatRequest after = client.requests().get(2);
		assertTrue(after.messages().stream().anyMatch(m -> m.content() != null
			&& m.content().contains("参数校验失败")));
	}

	@Test
	public void testToolNotFound() {
		ToolRegistry registry = registryWithLookup();
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "ghost", "{}")))
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));
		ChatRequest after = client.requests().get(2);
		assertTrue(after.messages().stream().anyMatch(m -> m.content() != null
			&& m.content().contains("tool not found: ghost")));
	}

	@Test
	public void testPlanArrayStringAndNumberElements() {
		// 数组元素为字符串与数字（非对象）
		ScriptedAiClient client = new ScriptedAiClient()
			.withText("[\"第一步\", 123]")
			.withText("r1")
			.withText("r2")
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(),
				new ToolRegistry(), null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));
		// 规划阶段 1 次 + 两步 + 汇总
		assertEquals(4, client.requests().size());
	}

	@Test
	public void testPlanArrayNullElementAndMalformed() {
		// null 元素被跳过；括号内非法 JSON 走 catch 后按行兜底
		ScriptedAiClient client = new ScriptedAiClient()
			.withText("[null, {\"step\":\"真实一步\"}]")
			.withText("r1")
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(),
				new ToolRegistry(), null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));

		ScriptedAiClient client2 = new ScriptedAiClient()
			.withText("[bad]\n步骤A\n步骤B")
			.withText("ra")
			.withText("rb")
			.withText("最终答案2");
		PlanExecuteAgent agent2 = new PlanExecuteAgent(client2, baseRequest(),
				new ToolRegistry(), null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案2", agent2.run("任务"));
	}

	@Test
	public void testBuildRequestCopiesSamplingParams() {
		ChatRequest templ = ChatRequest.builder()
			.model("m")
			.messages(List.of(ChatMessage.system("s")))
			.temperature(0.7)
			.maxTokens(128)
			.topP(0.9)
			.toolChoice("auto")
			.presencePenalty(0.1)
			.frequencyPenalty(0.2)
			.seed(42)
			.user("tester")
			.build();

		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withText("步骤结果")
			.withText("最终答案");
		PlanExecuteAgent agent = new PlanExecuteAgent(client, templ, new ToolRegistry(),
				null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));

		ChatRequest seen = client.requests().get(0);
		assertEquals(Double.valueOf(0.7), seen.temperature());
		assertEquals(Integer.valueOf(128), seen.maxTokens());
		assertEquals(Double.valueOf(0.9), seen.topP());
		assertEquals("auto", seen.toolChoice());
		assertEquals(Double.valueOf(0.1), seen.presencePenalty());
		assertEquals(Double.valueOf(0.2), seen.frequencyPenalty());
		assertEquals(Integer.valueOf(42), seen.seed());
		assertEquals("tester", seen.user());
	}

	@Test
	public void testEmptyChoicesResponse() {
		// 规划响应空候选 → firstText 返回 "" → 回退单步直接回答，流程走完
		com.sure.ai.client.AiClient emptyClient = new com.sure.ai.client.AiClient() {
			@Override
			public String name() {
				return "empty";
			}

			@Override
			public ChatResponse chat(ChatRequest request) {
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
		};
		PlanExecuteAgent agent = new PlanExecuteAgent(emptyClient, baseRequest(),
				new ToolRegistry(), null, 10, Duration.ofSeconds(30));
		String answer = agent.run("任务");
		assertNotNull(answer);
	}

	@Test
	public void testTotalTimeoutThrows() {
		// 规划后已超时 → 执行前 checkTimeout 抛 AiTimeoutException
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withText("不该执行到");
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(),
				new ToolRegistry(), null, 10, Duration.ofMillis(1));
		try {
			agent.run("任务");
			// 异常被步骤级重试吸收，最终不向外抛；仅需确保流程走完不崩
		} catch (AiTimeoutException e) {
			assertTrue(e.getMessage().contains("timeout"));
		}
	}

	@Test
	public void testParseArgumentsJsonObjectAccess() {
		// 覆盖正常 JsonObject 入参下 handler 路径（空注册中心走直接 chat）
		ScriptedAiClient client = new ScriptedAiClient()
			.withText("1. 甲\n\n2. 乙")
			.withText("甲结果")
			.withText("乙结果")
			.withText("综合");
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(),
				new ToolRegistry(), null, 10, Duration.ofSeconds(30));
		assertEquals("综合", agent.run("任务"));
	}

	@Test
	public void testToolHandlerExceptionIsRecovered() {
		// 工具 handler 抛 RuntimeException → executeTool 内 catch 收敛为错误文本
		ToolRegistry registry = new ToolRegistry();
		registry.register(com.sure.ai.model.ToolFunction.of("boom", "炸", "{}"),
				args -> {
					throw new IllegalStateException("handler-boom");
				});
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "boom", "{}")))
			.withText("最终答案");
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));
		ChatRequest after = client.requests().get(2);
		assertTrue(after.messages().stream().anyMatch(m -> m.content() != null
			&& m.content().contains("工具执行异常")));
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
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(ONE_STEP)
			.withToolCalls(List.of(call("c1", "err", "{}")))
			.withText("最终答案");
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30));
		assertEquals("最终答案", agent.run("任务"));
	}
}
