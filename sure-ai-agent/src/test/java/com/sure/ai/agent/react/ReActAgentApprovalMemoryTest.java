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
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sure.ai.agent.approval.AllToolsApprovalPolicy;
import com.sure.ai.agent.approval.ApprovalDecision;
import com.sure.ai.agent.approval.ApprovalGate;
import com.sure.ai.agent.approval.ApprovalHandler;
import com.sure.ai.agent.approval.ApprovalRequest;
import com.sure.ai.agent.approval.AutoApprovalHandler;
import com.sure.ai.agent.approval.AutoRejectHandler;
import com.sure.ai.agent.approval.TimeoutApprovalHandler;
import com.sure.ai.agent.memory.longterm.LongTermMemory;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import org.junit.Test;

/**
 * ReActAgent 与 HITL 审批 / 长期记忆集成测试（FakeAiClient 零网络）。
 */
public class ReActAgentApprovalMemoryTest {

	private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

	private static ChatRequest baseRequest() {
		return ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.system("你是助手")))
			.build();
	}

	private static ToolCall call(String id, String name) {
		return new ToolCall(id, name, "{}");
	}

	/** 构造带审批门的 agent。 */
	private static ReActAgent agentWith(FakeAiClient client, ToolRegistry registry,
			ApprovalGate gate, LongTermMemory ltm) {
		return new ReActAgent(client, baseRequest(), registry, null, 10,
			Duration.ofSeconds(30), null, null, "sess-1", gate, ltm);
	}

	@Test
	public void testAgentRejectsTool() {
		ToolRegistry registry = new ToolRegistry();
		AtomicBoolean executed = new AtomicBoolean(false);
		registry.register(ToolFunction.of("dangerous", "危险写操作", SCHEMA),
			args -> {
				executed.set(true);
				return "不该返回";
			});

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "dangerous")))
			.withText("好的，已按拒绝情况回答。");

		ApprovalGate gate = new ApprovalGate(AllToolsApprovalPolicy.instance(),
			AutoRejectHandler.instance());
		ReActAgent agent = agentWith(client, registry, gate, null);
		String answer = agent.run("执行危险操作");

		assertEquals("好的，已按拒绝情况回答。", answer);
		// 工具未被执行
		assertTrue(!executed.get());
		// 第二轮请求历史中应包含拒绝回灌文本
		ChatRequest second = client.requests().get(1);
		boolean hasRejection = second.messages().stream()
			.anyMatch(m -> m.toolCallId() != null
				&& m.content() != null && m.content().contains("用户拒绝执行工具 dangerous"));
		assertTrue(hasRejection);
	}

	@Test
	public void testAgentApprovesTool() {
		ToolRegistry registry = new ToolRegistry();
		AtomicInteger executed = new AtomicInteger();
		registry.register(ToolFunction.of("dangerous", "危险写操作", SCHEMA),
			args -> {
				executed.incrementAndGet();
				return "执行成功";
			});

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "dangerous")))
			.withText("操作完成。");

		ApprovalGate gate = new ApprovalGate(AllToolsApprovalPolicy.instance(),
			AutoApprovalHandler.instance());
		ReActAgent agent = agentWith(client, registry, gate, null);
		String answer = agent.run("执行危险操作");

		assertEquals("操作完成。", answer);
		assertEquals(1, executed.get());
	}

	@Test
	public void testAgentTimeoutTool() {
		ToolRegistry registry = new ToolRegistry();
		AtomicBoolean executed = new AtomicBoolean(false);
		registry.register(ToolFunction.of("slow", "慢工具", SCHEMA),
			args -> {
				executed.set(true);
				return "不应执行";
			});

		ApprovalHandler blocking = new ApprovalHandler() {
			@Override
			public ApprovalDecision request(ApprovalRequest request) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return ApprovalDecision.approved();
			}

			@Override
			public String name() {
				return "blocking";
			}
		};
		ApprovalGate gate = new ApprovalGate(AllToolsApprovalPolicy.instance(),
			new TimeoutApprovalHandler(blocking, Duration.ofMillis(100)));

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "slow")))
			.withText("审批超时了，我换个方式。");

		ReActAgent agent = agentWith(client, registry, gate, null);
		String answer = agent.run("调用慢工具");

		assertEquals("审批超时了，我换个方式。", answer);
		assertTrue(!executed.get());
		ChatRequest second = client.requests().get(1);
		boolean hasTimeout = second.messages().stream()
			.anyMatch(m -> m.toolCallId() != null
				&& m.content() != null && m.content().contains("审批超时"));
		assertTrue(hasTimeout);
	}

	@Test
	public void testRejectReasonPropagated() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("pay", "支付", SCHEMA),
			args -> "不该执行");

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "pay")))
			.withText("已取消支付。");

		ApprovalGate gate = new ApprovalGate(AllToolsApprovalPolicy.instance(),
			AutoRejectHandler.withReason("余额不足"));
		ReActAgent agent = agentWith(client, registry, gate, null);
		agent.run("转账 100 元");

		ChatRequest second = client.requests().get(1);
		boolean hasReason = second.messages().stream()
			.anyMatch(m -> m.toolCallId() != null
				&& m.content() != null && m.content().contains("余额不足"));
		assertTrue(hasReason);
	}

	@Test
	public void testAgentRecallsBeforeRun() {
		// 预先沉淀一条长期记忆
		LongTermMemory ltm = new LongTermMemory(null, null, null, null);
		ltm.remember(List.of(ChatMessage.user("我喜欢喝美式咖啡")), "old-session");
		assertEquals(1, ltm.all().size());

		ToolRegistry registry = new ToolRegistry();
		FakeAiClient client = new FakeAiClient().withText("美式咖啡适合您。");

		ReActAgent agent = agentWith(client, registry, null, ltm);
		String answer = agent.run("美式咖啡");
		assertEquals("美式咖啡适合您。", answer);

		// 第一条请求：base(system) → 召回 system → 当前 user
		List<ChatMessage> msgs = client.lastRequest().messages();
		assertEquals(3, msgs.size());
		assertEquals("你是助手", msgs.get(0).content());
		assertTrue(msgs.get(1).content().startsWith("相关长期记忆"));
		assertTrue(msgs.get(1).content().contains("美式咖啡"));
		assertEquals("user", msgs.get(2).role().name().toLowerCase());
	}

	@Test
	public void testAgentRemembersAfterRun() {
		LongTermMemory ltm = new LongTermMemory(null, null, null, null);
		ToolRegistry registry = new ToolRegistry();
		FakeAiClient client = new FakeAiClient().withText("您的订单号是 A123。");

		ReActAgent agent = agentWith(client, registry, null, ltm);
		agent.run("帮我查一下订单");

		// run 后应沉淀出记忆（user 提问 + assistant 答案）
		assertTrue("应至少沉淀 1 条, actual=" + ltm.all().size(), ltm.all().size() >= 1);
		boolean hasUserFact = ltm.all().stream()
			.anyMatch(e -> e.content().contains("帮我查一下订单"));
		assertTrue(hasUserFact);
	}

	@Test
	public void testNoGateBackwardCompatible() {
		// 不传 gate / longTermMemory：行为与历史一致
		ToolRegistry registry = new ToolRegistry();
		AtomicInteger executed = new AtomicInteger();
		registry.register(ToolFunction.of("f", "f", SCHEMA),
			args -> {
				executed.incrementAndGet();
				return "ok";
			});
		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "f")))
			.withText("完成");
		// 旧 9 参构造器
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
			null, 10, Duration.ofSeconds(30), null, null, null);
		String answer = agent.run("调用 f");
		assertEquals("完成", answer);
		assertEquals(1, executed.get());
	}
}
