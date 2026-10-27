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

package com.sure.ai.agent.delegation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.memory.ConversationMemory;
import com.sure.ai.agent.memory.InMemoryConversationMemory;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolCall;
import org.junit.Test;

/**
 * {@link AgentAsTool} 层级委派核心测试（ScriptedClient 零真实网络）。
 */
public class AgentAsToolTest {

	private static ChatRequest baseRequest() {
		return ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.system("你是助手")))
			.build();
	}

	private static ToolCall call(String id, String name, String args) {
		return new ToolCall(id, name, args);
	}

	/** 构造一个空注册表、仅返回固定文本的叶子 ReAct 子 Agent。 */
	private static ScriptedClient leafAgent(AgentRunner[] holder, String id, String answer) {
		ScriptedClient client = new ScriptedClient().withText(answer);
		ReActAgent agent = new ReActAgent(client, baseRequest(), new ToolRegistry());
		holder[0] = AgentRunner.of(agent, id, "leaf " + id);
		return client;
	}

	@Test
	public void singleLevelDelegationReturnsChildAnswerAndParentContinues() {
		AgentRunner[] child = new AgentRunner[1];
		ScriptedClient childClient = leafAgent(child, "sub_a", "子A：西安晴 26℃");

		ToolRegistry parentReg = new ToolRegistry();
		AgentAsTool.of("sub_a", "天气专职子Agent", ChildAgentFactory.fixed(child[0]))
			.register(parentReg);

		ScriptedClient parentClient = new ScriptedClient()
			.withToolCalls(List.of(call("c1", "sub_a", "{\"task\":\"查西安天气\"}")))
			.withText("父综合：子A已给出，西安晴 26℃。");

		ReActAgent parent = new ReActAgent(parentClient, baseRequest(), parentReg);
		String answer = parent.run("天气如何？");

		assertEquals("父综合：子A已给出，西安晴 26℃。", answer);
		// 子 Agent 确实收到了父转来的任务描述
		String childTask = childClient.lastRequest().messages().stream()
			.filter(m -> m.content() != null && m.content().contains("查西安天气"))
			.findFirst().orElseThrow().content();
		assertTrue(childTask.contains("查西安天气"));
	}

	@Test
	public void twoLevelNestingPropagatesResultsUp() {
		// B 叶子
		AgentRunner[] b = new AgentRunner[1];
		ScriptedClient bClient = leafAgent(b, "sub_b", "B产出：报告正文");

		// A：注册表注册委派 B 的工具
		ToolRegistry aReg = new ToolRegistry();
		AgentAsTool.of("sub_b", "写作子Agent", ChildAgentFactory.fixed(b[0])).register(aReg);
		ScriptedClient aClient = new ScriptedClient()
			.withToolCalls(List.of(call("a1", "sub_b", "{\"task\":\"撰写报告\"}")))
			.withText("A综合了B的报告正文");
		ReActAgent aAgent = new ReActAgent(aClient, baseRequest(), aReg);
		AgentRunner a = AgentRunner.of(aAgent, "sub_a", "分析子Agent");

		// P：注册表注册委派 A 的工具
		ToolRegistry pReg = new ToolRegistry();
		AgentAsTool.of("sub_a", "分析子Agent", ChildAgentFactory.fixed(a)).register(pReg);
		ScriptedClient pClient = new ScriptedClient()
			.withToolCalls(List.of(call("p1", "sub_a", "{\"task\":\"总任务\"}")))
			.withText("父最终答案");
		ReActAgent parent = new ReActAgent(pClient, baseRequest(), pReg);

		assertEquals("父最终答案", parent.run("总任务"));
		assertEquals(1, bClient.requests().size());
		assertEquals(2, aClient.requests().size());
	}

	@Test
	public void threeLevelNestingChainsThroughThreeAgents() {
		// C 叶子
		AgentRunner[] c = new AgentRunner[1];
		ScriptedClient cClient = leafAgent(c, "sub_c", "C产出：终稿");

		// B 委派 C
		ToolRegistry bReg = new ToolRegistry();
		AgentAsTool.of("sub_c", "定稿子", ChildAgentFactory.fixed(c[0])).register(bReg);
		ScriptedClient bClient = new ScriptedClient()
			.withToolCalls(List.of(call("b1", "sub_c", "{\"task\":\"定稿\"}")))
			.withText("B汇总了C终稿");
		ReActAgent bAgent = new ReActAgent(bClient, baseRequest(), bReg);
		AgentRunner b = AgentRunner.of(bAgent, "sub_b", "校对子");

		// A 委派 B
		ToolRegistry aReg = new ToolRegistry();
		AgentAsTool.of("sub_b", "校对子", ChildAgentFactory.fixed(b)).register(aReg);
		ScriptedClient aClient = new ScriptedClient()
			.withToolCalls(List.of(call("a1", "sub_b", "{\"task\":\"校对\"}")))
			.withText("A汇总了B校对结果");
		ReActAgent aAgent = new ReActAgent(aClient, baseRequest(), aReg);
		AgentRunner a = AgentRunner.of(aAgent, "sub_a", "分析子");

		// P 委派 A
		ToolRegistry pReg = new ToolRegistry();
		AgentAsTool.of("sub_a", "分析子", ChildAgentFactory.fixed(a)).register(pReg);
		ScriptedClient pClient = new ScriptedClient()
			.withToolCalls(List.of(call("p1", "sub_a", "{\"task\":\"总任务\"}")))
			.withText("父最终答案");
		ReActAgent parent = new ReActAgent(pClient, baseRequest(), pReg);

		assertEquals("父最终答案", parent.run("总任务"));
		assertEquals(1, cClient.requests().size());
		assertEquals(2, bClient.requests().size());
		assertEquals(2, aClient.requests().size());
	}

	@Test
	public void parentRegistersTwoSiblingChildTools() {
		AgentRunner[] childX = new AgentRunner[1];
		ScriptedClient xClient = leafAgent(childX, "child_x", "X结果");
		AgentRunner[] childY = new AgentRunner[1];
		ScriptedClient yClient = leafAgent(childY, "child_y", "Y结果");

		ToolRegistry pReg = new ToolRegistry();
		AgentAsTool.of("child_x", "X子", ChildAgentFactory.fixed(childX[0])).register(pReg);
		AgentAsTool.of("child_y", "Y子", ChildAgentFactory.fixed(childY[0])).register(pReg);

		ScriptedClient pClient = new ScriptedClient()
			.withToolCalls(List.of(
				call("p1", "child_x", "{\"task\":\"x任务\"}"),
				call("p2", "child_y", "{\"task\":\"y任务\"}")))
			.withText("父综合了X与Y");
		ReActAgent parent = new ReActAgent(pClient, baseRequest(), pReg,
			null, 10, Duration.ofSeconds(10));

		assertEquals("父综合了X与Y", parent.run("双任务"));
		assertEquals(1, xClient.requests().size());
		assertEquals(1, yClient.requests().size());
	}

	@Test
	public void depthLimitRejectedAtSecondLevel() throws Exception {
		// 内层工具（默认深度），外层把天花板压到 1
		AgentAsTool inner = AgentAsTool.of("inner", "内层",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> "叶答案")));
		AgentAsTool outer = AgentAsTool.builder("outer", "外层",
				ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子",
					t -> runHandler(inner))))
			.maxDepth(1)
			.build();

		DelegationDepthExceededException ex = assertThrows(DelegationDepthExceededException.class,
			() -> outer.toHandler().execute(Json.object()));
		assertTrue(ex.getMessage().contains("maxDepth=1"));
	}

	@Test
	public void directCycleDetected() throws Exception {
		AgentAsTool[] selfHolder = new AgentAsTool[1];
		selfHolder[0] = AgentAsTool.of("self", "自环",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子",
				t -> runHandler(selfHolder[0]))));

		DelegationCycleException ex = assertThrows(DelegationCycleException.class,
			() -> selfHolder[0].toHandler().execute(Json.object()));
		assertTrue(ex.getMessage().contains("self"));
		assertTrue(ex.chain().contains("self"));
	}

	@Test
	public void indirectCycleDetectedAcrossTwoTools() throws Exception {
		AgentAsTool[] bHolder = new AgentAsTool[1];
		AgentAsTool a = AgentAsTool.of("a", "A",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子",
				t -> runHandler(bHolder[0]))));
		AgentAsTool bTool = AgentAsTool.of("b", "B",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子",
				t -> runHandler(a))));
		bHolder[0] = bTool;

		DelegationCycleException ex = assertThrows(DelegationCycleException.class,
			() -> a.toHandler().execute(Json.object()));
		assertTrue(ex.chain().contains("a"));
		assertTrue(ex.chain().contains("b"));
	}

	@Test
	public void childExceptionWrappedPreservesCause() {
		IllegalStateException boom = new IllegalStateException("子Agent炸了");
		AgentAsTool tool = AgentAsTool.of("bad", "坏子",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> {
				throw boom;
			})));

		DelegationException ex = assertThrows(DelegationException.class,
			() -> tool.toHandler().execute(Json.object()));
		assertSame(boom, ex.getCause());
		assertTrue(ex.getMessage().contains("bad"));
	}

	@Test
	public void childFailureAbsorbedByParentAndContinues() {
		ToolRegistry reg = new ToolRegistry();
		AgentAsTool.of("bad", "坏子",
				ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> {
					throw new IllegalStateException("炸了");
				})))
			.register(reg);

		AtomicInteger errors = new AtomicInteger();
		AgentListener listener = new AgentListener() {
			@Override
			public void onError(Throwable e) {
				errors.incrementAndGet();
			}
		};
		ScriptedClient client = new ScriptedClient()
			.withToolCalls(List.of(call("c1", "bad", "{}")))
			.withText("子Agent出错后父模型重新作答");
		ReActAgent parent = new ReActAgent(client, baseRequest(), reg, listener,
			10, Duration.ofSeconds(10));

		String answer = parent.run("任务");
		assertEquals("子Agent出错后父模型重新作答", answer);
		assertTrue(errors.get() >= 1);
	}

	@Test
	public void childEventsBubbleToParentListenerWithContext() {
		DelegationContext[] captured = new DelegationContext[1];
		// 子 ReAct：先调一个内置工具再作答
		ToolRegistry childReg = new ToolRegistry();
		childReg.register(com.sure.ai.model.ToolFunction.of("calc", "计算", "{}"),
			args -> "42");
		ScriptedClient childClient = new ScriptedClient()
			.withToolCalls(List.of(call("ch1", "calc", "{}")))
			.withText("子最终答案");

		List<String> seenToolCalls = new ArrayList<>();
		List<String> seenFinish = new ArrayList<>();
		AgentAsTool tool = AgentAsTool.builder("sub_a", "冒泡子",
				sink -> {
					if (sink instanceof ForwardingDelegationListener f) {
						captured[0] = f.context();
					}
					ReActAgent child = new ReActAgent(childClient, baseRequest(),
						childReg, sink, 10, Duration.ofSeconds(10));
					return AgentRunner.of(child, "sub_a", "冒泡子");
				})
			.parentListener(new AgentListener() {
				@Override
				public void onToolCall(ToolCall toolCall) {
					seenToolCalls.add(toolCall.name());
				}

				@Override
				public void onFinish(String finalAnswer) {
					seenFinish.add(finalAnswer);
				}
			})
			.build();

		ToolRegistry pReg = new ToolRegistry();
		tool.register(pReg);
		ScriptedClient pClient = new ScriptedClient()
			.withToolCalls(List.of(call("p1", "sub_a", "{\"task\":\"冒泡任务\"}")))
			.withText("父收尾");
		ReActAgent parent = new ReActAgent(pClient, baseRequest(), pReg);

		assertEquals("父收尾", parent.run("冒泡任务"));
		// 子内部 calc 工具调用冒泡到父监听者
		assertTrue(seenToolCalls.contains("calc"));
		assertEquals(1, seenFinish.size());
		assertNotNull(captured[0]);
		assertEquals("sub_a", captured[0].sourceAgentId());
		assertEquals(0, captured[0].depth());
		assertEquals("冒泡任务", captured[0].parentTask());
		assertNotNull(captured[0].chainId());
	}

	@Test
	public void isolatedMemoryDoesNotLeakBetweenAgents() {
		ConversationMemory childMemory = new InMemoryConversationMemory(20);
		ReActAgent childAgent = new ReActAgent(new ScriptedClient().withText("子答案"),
			baseRequest(), new ToolRegistry(), null, 10, Duration.ofSeconds(10),
			childMemory);
		AgentRunner child = AgentRunner.of(childAgent, "leaf", "叶子");

		ConversationMemory parentMemory = new InMemoryConversationMemory(20);
		ToolRegistry pReg = new ToolRegistry();
		AgentAsTool.of("sub_a", "子", ChildAgentFactory.fixed(child)).register(pReg);
		ReActAgent parent = new ReActAgent(new ScriptedClient()
			.withToolCalls(List.of(call("p1", "sub_a", "{\"task\":\"父任务\"}")))
			.withText("父答案"), baseRequest(), pReg, null, 10, Duration.ofSeconds(10),
			parentMemory);

		parent.run("父任务");
		// 子记忆只含子自己的一轮，父记忆不含子答案
		assertEquals(2, childMemory.size());
		assertTrue(parentMemory.history().stream()
			.noneMatch(m -> m.content() != null && m.content().contains("子答案")));
	}

	@Test
	public void sharedMemoryPolicyRecorded() {
		AgentAsTool tool = AgentAsTool.builder("x", "x",
				ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> "r")))
			.memoryPolicy(MemoryPolicy.SHARED)
			.build();
		assertEquals(MemoryPolicy.SHARED, tool.memoryPolicy());

		AgentAsTool def = AgentAsTool.of("y", "y",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> "r")));
		assertEquals(MemoryPolicy.ISOLATED, def.memoryPolicy());
		assertEquals(AgentAsTool.DEFAULT_MAX_DEPTH, def.maxDepth());
	}

	@Test
	public void timeoutThrowsDelegationTimeoutException() {
		CountDownLatch latch = new CountDownLatch(1);
		AgentAsTool tool = AgentAsTool.builder("slow", "慢子",
				ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> {
					try {
						latch.await();
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
					return "不该返回";
				})))
			.timeout(Duration.ofMillis(100))
			.build();

		DelegationTimeoutException ex = assertThrows(DelegationTimeoutException.class,
			() -> tool.toHandler().execute(Json.object()));
		assertTrue(ex.getMessage().contains("slow"));
		latch.countDown();
	}

	@Test
	public void concurrentTopLevelDelegationsAreIndependent() throws Exception {
		AgentRunner leaf = AgentRunner.of("leaf", "叶子", t -> "答案-" + t);
		ToolRegistry reg = new ToolRegistry();
		AgentAsTool.of("worker", "工作子", ChildAgentFactory.fixed(leaf)).register(reg);

		int n = 20;
		CountDownLatch done = new CountDownLatch(n);
		List<String> answers = new java.util.concurrent.CopyOnWriteArrayList<>();
		for (int i = 0; i < n; i++) {
			final int k = i;
			Thread.startVirtualThread(() -> {
				try {
					JsonObject args = Json.object();
					args.put("task", "任务" + k);
					String out = (String) reg.getHandler("worker").orElseThrow()
						.execute(args);
					answers.add(out);
				} catch (Exception e) {
					answers.add("ERR:" + e.getMessage());
				} finally {
					done.countDown();
				}
			});
		}
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(n, answers.size());
		assertTrue(answers.stream().noneMatch(a -> a.startsWith("ERR")));
	}

	@Test
	public void failedDelegationCleansScopeSoNextRunIsClean() throws Exception {
		AgentAsTool bad = AgentAsTool.of("bad", "坏",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> {
				throw new IllegalStateException("x");
			})));
		assertThrows(DelegationException.class,
			() -> bad.toHandler().execute(Json.object()));

		// 失败出栈后，再次顶层委派不应残留旧链导致误判环
		AgentRunner good = AgentRunner.of("good", "好子", t -> "好答案");
		String out = (String) AgentAsTool.of("good", "好",
			ChildAgentFactory.fixed(good)).toHandler().execute(Json.object());
		assertEquals("好答案", out);
	}

	/** 在 Function 句柄内安全调用工具处理器：运行时异常原样抛，受检异常包成运行时。 */
	private static String runHandler(AgentAsTool tool) {
		try {
			return tool.toHandler().execute(Json.object());
		} catch (RuntimeException re) {
			throw re;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
