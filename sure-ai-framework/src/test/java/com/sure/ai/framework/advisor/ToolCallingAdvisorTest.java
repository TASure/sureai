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
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sure.ai.framework.FrameworkUtil;
import com.sure.ai.framework.annotation.AiService;
import com.sure.ai.framework.annotation.Tool;
import com.sure.ai.framework.annotation.UserMessage;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Role;
import com.sure.ai.model.ToolCall;

import org.junit.Test;

/**
 * {@link ToolCallingAdvisor} 工具循环测试（零真实网络）。
 *
 * @author sureai
 * @since 2.5.0
 */
public class ToolCallingAdvisorTest {

	/** 带 default @Tool 方法的服务：工具体就在接口里，由 ReflectionToolExecutor 执行。 */
	@AiService(model = "m")
	interface Calc {

		@UserMessage("{q}")
		String chat(String q);

		@Tool(description = "两数之和")
		default int add(int a, int b) {
			return a + b;
		}
	}

	/** 会抛异常的工具（失败隔离用）。 */
	@AiService(model = "m")
	interface Flaky {

		@UserMessage("{q}")
		String chat(String q);

		@Tool(description = "总是失败")
		default int boom(int a) {
			throw new IllegalStateException("工具炸了");
		}
	}

	@Test
	public void toolLoopExecutesDefaultToolThenFinalAnswer() {
		ToolCall call = ToolCall.of("1", "add", "{\"a\":2,\"b\":3}");
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.toolCalls(List.of(call)))
			.then(ScriptedClient.text("两数之和是 5"));

		Calc ai = FrameworkUtil.builder(Calc.class, client)
			.advisors(List.of(new ToolCallingAdvisor())).build();

		String out = ai.chat("2+3 等于几");
		assertEquals("两数之和是 5", out);
		assertEquals(2, client.callCount());

		ChatRequest second = client.requests().get(1);
		List<ChatMessage> msgs = second.messages();
		// user + assistant(toolCalls) + tool(result)
		assertEquals(3, msgs.size());
		assertEquals(Role.ASSISTANT, msgs.get(1).role());
		assertEquals(1, msgs.get(1).toolCalls().size());
		assertEquals(Role.TOOL, msgs.get(2).role());
		assertEquals("1", msgs.get(2).toolCallId());
		assertEquals("5", msgs.get(2).content());
	}

	@Test
	public void toolFailureIsolatedAndFedBackToModel() {
		ToolCall call = ToolCall.of("9", "boom", "{\"a\":1}");
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.toolCalls(List.of(call)))
			.then(ScriptedClient.text("工具失败了，我改答：无结果"));

		Flaky ai = FrameworkUtil.builder(Flaky.class, client)
			.advisors(List.of(new ToolCallingAdvisor())).build();

		String out = ai.chat("算一下");
		assertEquals("工具失败了，我改答：无结果", out);
		assertEquals(2, client.callCount());
		String toolResult = client.requests().get(1).messages().get(2).content();
		assertTrue("应把工具错误回灌模型: " + toolResult, toolResult.contains("工具炸了"));
	}

	@Test
	public void maxIterationsStopsLoopAndReturnsLastResponse() {
		// 模型每次都返回 tool_calls，永不收敛；maxIterations=1 应在 2 次调用后停止
		ChatResponse looping = ScriptedClient.toolCalls(
			List.of(ToolCall.of("1", "add", "{\"a\":1,\"b\":1}")));
		ScriptedClient client = new ScriptedClient().repeat(looping);

		Calc ai = FrameworkUtil.builder(Calc.class, client)
			.advisors(List.of(new ToolCallingAdvisor(1))).build();

		ai.chat("loop");
		assertEquals("首调 + 1 次重试后达上限", 2, client.callCount());
	}

	@Test
	public void advisorLevelLoopWithFakeExecutor() {
		AtomicInteger executions = new AtomicInteger();
		ToolExecutor executor = (name, args) -> {
			executions.incrementAndGet();
			return "exec:" + name;
		};
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.toolCalls(List.of(ToolCall.of("1", "x", "{}"))))
			.then(ScriptedClient.toolCalls(List.of(ToolCall.of("2", "y", "{}"))))
			.then(ScriptedClient.text("final"));

		ChatRequest req = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi"))).build();
		AdvisorContext ctx = new AdvisorContext(req, executor, null);
		AdvisorChain chain = new AdvisorChain(List.of(new ToolCallingAdvisor(5)),
			c -> client.chat(c.rebuildRequest()));

		ChatResponse resp = chain.execute(ctx);
		assertEquals("final", resp.firstText());
		assertEquals(3, client.callCount());
		assertEquals(2, executions.get());
	}

	@Test
	public void noExecutorMeansNoLoop() {
		ScriptedClient client = new ScriptedClient()
			.then(ScriptedClient.toolCalls(List.of(ToolCall.of("1", "x", "{}"))));
		ChatRequest req = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi"))).build();
		AdvisorContext ctx = new AdvisorContext(req, null, null);
		AdvisorChain chain = new AdvisorChain(List.of(new ToolCallingAdvisor()),
			c -> client.chat(c.rebuildRequest()));

		ChatResponse resp = chain.execute(ctx);
		// 无执行器：尽管响应带 tool_calls 也不循环，直接原样返回
		assertEquals(1, client.callCount());
		assertTrue(resp.choices().get(0).message().toolCalls() != null);
	}
}
