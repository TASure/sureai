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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.memory.ConversationMemory;
import com.sure.ai.agent.memory.InMemoryConversationMemory;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import org.junit.Test;

/**
 * {@link PlanExecuteAgent} 完整实现单元测试（{@link ScriptedAiClient} 零真实网络）。
 */
public class PlanExecuteAgentTest {

	private static final String PLAN_2_STEPS = """
			[{"step":"查询天气","description":"查西安天气"},
			 {"step":"综合回答","description":"根据结果作答"}]
			""";

	private static ChatRequest baseRequest() {
		return ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.system("你是助手")))
			.build();
	}

	private static ToolRegistry emptyRegistry() {
		return new ToolRegistry();
	}

	/** 计数用监听器。 */
	private static final class CountingListener implements AgentListener {
		final AtomicInteger planGenerated = new AtomicInteger();
		final AtomicInteger stepStart = new AtomicInteger();
		final AtomicInteger stepComplete = new AtomicInteger();
		final AtomicInteger finish = new AtomicInteger();
		final AtomicInteger thought = new AtomicInteger();
		final AtomicInteger error = new AtomicInteger();
		final AtomicInteger toolCall = new AtomicInteger();
		final List<String> steps = new ArrayList<>();

		@Override
		public void onThought(String t) {
			this.thought.incrementAndGet();
		}

		@Override
		public void onPlanGenerated(List<String> s) {
			this.planGenerated.incrementAndGet();
			this.steps.addAll(s);
		}

		@Override
		public void onStepStart(int index, String step) {
			this.stepStart.incrementAndGet();
		}

		@Override
		public void onStepComplete(int index, String result) {
			this.stepComplete.incrementAndGet();
		}

		@Override
		public void onToolCall(com.sure.ai.model.ToolCall c) {
			this.toolCall.incrementAndGet();
		}

		@Override
		public void onFinish(String finalAnswer) {
			this.finish.incrementAndGet();
		}

		@Override
		public void onError(Throwable e) {
			this.error.incrementAndGet();
		}
	}

	@Test
	public void testPlanExecuteFullChain() {
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(PLAN_2_STEPS)
			.withText("步骤1：晴")
			.withText("步骤2：26℃")
			.withText("西安晴，26℃。");

		CountingListener listener = new CountingListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("西安天气如何？");

		assertEquals("西安晴，26℃。", answer);
		// 规划 + 两步执行 + 汇总 = 4 次请求
		assertEquals(4, client.requests().size());
		assertEquals(1, listener.planGenerated.get());
		assertEquals(2, listener.stepStart.get());
		assertEquals(2, listener.stepComplete.get());
		assertEquals(1, listener.finish.get());
		assertEquals(2, listener.steps.size());
		assertTrue(listener.steps.get(0).contains("查询天气"));
	}

	@Test
	public void testPlanParseFallbackLines() {
		ScriptedAiClient client = new ScriptedAiClient()
			.withText("1. 查询天气\n2. 查询气温")
			.withText("步骤1结果")
			.withText("步骤2结果")
			.withText("最终答案");

		CountingListener listener = new CountingListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("任务");

		assertEquals("最终答案", answer);
		assertEquals(List.of("查询天气", "查询气温"), listener.steps);
	}

	@Test
	public void testPlanParseFallbackSingle() {
		ScriptedAiClient client = new ScriptedAiClient()
			.withText("无法解析的一整句话没有换行也没有方括号")
			.withText("步骤结果")
			.withText("最终答案");

		CountingListener listener = new CountingListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("我的原始任务");

		assertEquals("最终答案", answer);
		// 回退为单步「直接回答」，步骤即原始任务
		assertEquals(1, listener.steps.size());
		assertEquals("我的原始任务", listener.steps.get(0));
		assertEquals(3, client.requests().size());
	}

	@Test
	public void testMaxStepsExceeded() {
		String plan4 = """
				[{"step":"a"},{"step":"b"},{"step":"c"},{"step":"d"}]
				""";
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(plan4);

		// maxSteps=3，计划有 4 步 → 抛 AiException
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				null, 3, Duration.ofSeconds(30));
		AiException ex = assertThrows(AiException.class, () -> agent.run("任务"));
		assertTrue(ex.getMessage().contains("maxSteps"));
		// 仅规划阶段发起了一次请求，尚未开始执行
		assertEquals(1, client.requests().size());
	}

	@Test
	public void testStepFailureRetry() {
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(PLAN_2_STEPS)
			.withText("步骤1：晴")
			.withError(new AiException("步骤2第一次失败"))
			.withText("步骤2重试后：26℃")
			.withText("西安晴，26℃。");

		CountingListener listener = new CountingListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("西安天气？");

		assertEquals("西安晴，26℃。", answer);
		// 异常被重试吸收，不中断整体流程
		assertEquals(1, listener.error.get());
		assertEquals(2, listener.stepComplete.get());
	}

	@Test
	public void testStepFailureRecoversAfterTwoErrors() {
		ScriptedAiClient client = new ScriptedAiClient()
			.withText("""
					[{"step":"唯一一步"}]
					""")
			.withError(new AiException("第一次炸"))
			.withError(new AiException("第二次炸"))
			.withText("最终答案");

		CountingListener listener = new CountingListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("任务");

		// 重试两次仍失败 → 记录错误继续走汇总，不抛异常
		assertEquals("最终答案", answer);
		assertEquals(2, listener.error.get());
		assertEquals(1, listener.stepComplete.get());
	}

	@Test
	public void testListenerEventSequence() {
		ScriptedAiClient client = new ScriptedAiClient()
			.withText(PLAN_2_STEPS)
			.withText("r1")
			.withText("r2")
			.withText("最终");

		CountingListener listener = new CountingListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				listener, 10, Duration.ofSeconds(30));
		agent.run("任务");

		assertEquals(1, listener.thought.get());
		assertEquals(1, listener.planGenerated.get());
		assertEquals(2, listener.stepStart.get());
		assertEquals(2, listener.stepComplete.get());
		assertEquals(1, listener.finish.get());
	}

	@Test
	public void testEmptyRegistryDirectAnswer() {
		ScriptedAiClient client = new ScriptedAiClient()
			.withText("""
					[{"step":"唯一一步"}]
					""")
			.withText("步骤结果")
			.withText("最终答案");

		CountingListener listener = new CountingListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("任务");

		assertEquals("最终答案", answer);
		// 空注册中心：全程无工具调用
		assertEquals(0, listener.toolCall.get());
		assertEquals(3, client.requests().size());
	}

	@Test
	public void testMemoryInjectedAndRecorded() {
		ConversationMemory memory = new InMemoryConversationMemory();
		memory.add(ChatMessage.user("之前聊过天气"));

		ScriptedAiClient client = new ScriptedAiClient()
			.withText("""
					[{"step":"一步"}]
					""")
			.withText("步骤结果")
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry(),
				null, 10, Duration.ofSeconds(30), memory);
		String answer = agent.run("新问题");

		assertEquals("最终答案", answer);
		// 规划请求中应包含 memory 历史
		ChatRequest planReq = client.requests().get(0);
		boolean hasMemory = planReq.messages().stream()
			.anyMatch(m -> "之前聊过天气".equals(m.content()));
		assertTrue(hasMemory);
		// 结束后记录 user + assistant
		assertEquals(3, memory.size());
		assertEquals("新问题", memory.history().get(1).content());
		assertEquals("最终答案", memory.history().get(2).content());
	}

	@Test
	public void testRunWithNoUserMessage() {
		ScriptedAiClient client = new ScriptedAiClient()
			.withText("""
					[{"step":"一步"}]
					""")
			.withText("步骤结果")
			.withText("最终答案");

		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), emptyRegistry());
		String answer = agent.run();
		assertEquals("最终答案", answer);
		assertEquals(3, client.requests().size());
	}

	@Test
	public void testToolStepLoop() {
		// registry 非空：执行步骤时模型先返回 tool_calls，再返回文本
		ToolRegistry registry = new ToolRegistry();
		registry.register(com.sure.ai.model.ToolFunction.of("lookup", "查询", "{}"),
				args -> "查到的数据");

		ScriptedAiClient client = new ScriptedAiClient()
			.withText("""
					[{"step":"查资料"}]
					""")
			.withToolCalls(List.of(com.sure.ai.model.ToolCall.of("c1", "lookup", "{}")))
			.withText("步骤结果")
			.withText("最终答案");

		CountingListener listener = new CountingListener();
		PlanExecuteAgent agent = new PlanExecuteAgent(client, baseRequest(), registry,
				listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("任务");

		assertEquals("最终答案", answer);
		assertEquals(1, listener.toolCall.get());
		// 规划 + (工具调用轮 + 文本轮) + 汇总 = 4
		assertEquals(4, client.requests().size());
		assertFalse(registry.isEmpty());
	}
}
