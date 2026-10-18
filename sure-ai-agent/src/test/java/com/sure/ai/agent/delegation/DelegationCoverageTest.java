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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ToolCall;
import org.junit.Test;

/**
 * 委派覆盖补全测试：超时成功/异常/中断、默认接口方法、上下文 child、冒泡全回调。
 */
public class DelegationCoverageTest {

	@Test
	public void timedSuccessReturnsAnswerAndExposesTimeout() throws Exception {
		AgentAsTool tool = AgentAsTool.builder("fast", "快子",
				ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> "快答案")))
			.timeout(Duration.ofSeconds(2))
			.build();
		assertEquals(Duration.ofSeconds(2), tool.timeout());
		assertEquals("快答案", tool.toHandler().execute(Json.object()));
	}

	@Test
	public void timedRuntimeExceptionWrappedWithCause() {
		IllegalStateException boom = new IllegalStateException("运行时炸");
		AgentAsTool tool = AgentAsTool.builder("boom", "爆子",
				ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> {
					throw boom;
				})))
			.timeout(Duration.ofSeconds(2))
			.build();
		DelegationException ex = assertThrows(DelegationException.class,
			() -> tool.toHandler().execute(Json.object()));
		assertSame(boom, ex.getCause());
	}

	@Test
	public void timedCheckedExceptionWrapped() {
		AgentRunner checked = new AgentRunner() {
			@Override
			public String run(String task) throws IOException {
				throw new IOException("io 失败");
			}
		};
		AgentAsTool tool = AgentAsTool.builder("io", "io子",
				ChildAgentFactory.fixed(checked))
			.timeout(Duration.ofSeconds(2))
			.build();
		DelegationException ex = assertThrows(DelegationException.class,
			() -> tool.toHandler().execute(Json.object()));
		assertTrue(ex.getCause() instanceof DelegationException);
		assertTrue(ex.getCause().getCause() instanceof IOException);
	}

	@Test
	public void interruptedDuringAwaitRaisedAsDelegationException() {
		// 子 Agent 确定性阻塞在永不主动释放的 latch 上，保证 future.get 进入阻塞等待点，
		// 再预置调用线程中断，稳定命中 InterruptedException → DelegationException，
		// 不依赖 sleep 时序。
		CountDownLatch blocker = new CountDownLatch(1);
		AgentAsTool tool = AgentAsTool.builder("slow2", "慢子",
				ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> {
					try {
						blocker.await();
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
					return "不该返回";
				})))
			.timeout(Duration.ofSeconds(2))
			.build();
		Thread.currentThread().interrupt();
		try {
			DelegationException ex = assertThrows(DelegationException.class,
				() -> tool.toHandler().execute(Json.object()));
			assertTrue(ex.getMessage().contains("被中断"));
		} finally {
			Thread.interrupted();
			blocker.countDown();
		}
	}

	@Test
	public void defaultAgentRunnerIdAndDescription() throws Exception {
		AgentRunner plain = new AgentRunner() {
			@Override
			public String run(String task) {
				return "r";
			}
		};
		assertTrue(plain.id().contains("DelegationCoverageTest"));
		assertEquals("", plain.description());
		assertEquals("r", plain.run("t"));
	}

	@Test
	public void contextChildAdvancesDepthAndAppendsAncestor() {
		Duration timeout = Duration.ofSeconds(5);
		DelegationContext root = new DelegationContext("c1", 0, "root", "父任务",
			List.of(), MemoryPolicy.ISOLATED, timeout);
		DelegationContext child = root.child("sub", "子任务");
		assertEquals("c1", child.chainId());
		assertEquals(1, child.depth());
		assertEquals("sub", child.sourceAgentId());
		assertEquals("子任务", child.parentTask());
		assertEquals(List.of("root"), child.ancestorIds());
		assertEquals(MemoryPolicy.ISOLATED, child.memoryPolicy());
		assertEquals(timeout, child.timeout());
	}

	@Test
	public void forwardingListenerPumpsAllCallbacksToParent() {
		DelegationContext ctx = new DelegationContext("c1", 1, "sub", "任务",
			List.of("root"), MemoryPolicy.SHARED, null);
		List<String> events = new ArrayList<>();
		AgentListener parent = new AgentListener() {
			@Override
			public void onThought(String thought) {
				events.add("thought:" + thought);
			}

			@Override
			public void onError(Throwable error) {
				events.add("error");
			}

			@Override
			public void onPlanGenerated(List<String> steps) {
				events.add("plan:" + steps.size());
			}

			@Override
			public void onStepStart(int index, String step) {
				events.add("start:" + index);
			}

			@Override
			public void onStepComplete(int index, String result) {
				events.add("complete:" + index);
			}

			@Override
			public void onToolCall(ToolCall toolCall) {
				events.add("tool:" + toolCall.name());
			}

			@Override
			public void onToolResult(ToolCall call, String result) {
				events.add("result");
			}

			@Override
			public void onFinish(String finalAnswer) {
				events.add("finish");
			}
		};
		ForwardingDelegationListener sink = new ForwardingDelegationListener(parent, ctx);
		assertSame(ctx, sink.context());
		sink.onThought("思考");
		sink.onError(new RuntimeException("e"));
		sink.onPlanGenerated(List.of("s1", "s2"));
		sink.onStepStart(0, "第一步");
		sink.onStepComplete(0, "结果");
		sink.onToolCall(new ToolCall("c1", "calc", "{}"));
		sink.onToolResult(new ToolCall("c1", "calc", "{}"), "42");
		sink.onFinish("答案");
		assertTrue(events.contains("thought:思考"));
		assertTrue(events.contains("error"));
		assertTrue(events.contains("plan:2"));
		assertTrue(events.contains("start:0"));
		assertTrue(events.contains("complete:0"));
		assertTrue(events.contains("tool:calc"));
		assertTrue(events.contains("result"));
		assertTrue(events.contains("finish"));
	}

	@Test
	public void nullParentListenerIsNoOp() {
		DelegationContext ctx = new DelegationContext("c1", 0, "x", "t",
			List.of(), MemoryPolicy.ISOLATED, null);
		ForwardingDelegationListener sink = new ForwardingDelegationListener(null, ctx);
		// 全部回调静默，不抛 NPE
		sink.onThought("t");
		sink.onError(new RuntimeException());
		sink.onPlanGenerated(List.of());
		sink.onStepStart(0, "s");
		sink.onStepComplete(0, "r");
		sink.onToolCall(new ToolCall("c", "n", "{}"));
		sink.onToolResult(new ToolCall("c", "n", "{}"), "r");
		sink.onFinish("f");
	}

	@Test
	public void parentListenerExceptionIsIsolated() {
		DelegationContext ctx = new DelegationContext("c1", 0, "x", "t",
			List.of(), MemoryPolicy.ISOLATED, null);
		AgentListener throwing = new AgentListener() {
			@Override
			public void onThought(String thought) {
				throw new RuntimeException("父监听者炸");
			}
		};
		ForwardingDelegationListener sink = new ForwardingDelegationListener(throwing, ctx);
		// 父监听者异常被隔离，不向子 Agent 扩散
		sink.onThought("t");
		sink.onFinish("f");
	}
}
