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

package com.sure.ai.agent.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.model.ToolCall;
import org.junit.Test;

/**
 * {@link com.sure.ai.agent.event} 包边界补充测试。
 *
 * <p>覆盖广播器订阅者异常隔离、清空与计数、流式监听器步骤事件映射、
 * 工具回灌错误判定与 arguments 解析兜底，以及各事件记录的 type。</p>
 */
public class EventEdgeTest {

	@Test
	public void testPublisherIsolatesThrowingSubscriber() {
		AgentEventPublisher publisher = new AgentEventPublisher();
		List<AgentEvent> ok = new ArrayList<>();
		publisher.subscribe(e -> {
			throw new IllegalStateException("sink-boom");
		});
		publisher.subscribe(ok::add);
		// 第一个订阅者抛异常被隔离，第二个照常收到
		publisher.publish(new ThoughtEvent("a", "t", 1L));
		assertEquals(1, ok.size());
		assertEquals(2, publisher.subscriberCount());
		publisher.clear();
		assertEquals(0, publisher.subscriberCount());
	}

	@Test
	public void testStreamingListenerStepEvents() {
		List<AgentEvent> received = new ArrayList<>();
		StreamingAgentListener listener = new StreamingAgentListener("agent", received::add);
		listener.onStepStart(0, "第一步");
		listener.onStepComplete(0, "结果A");
		assertEquals(2, received.size());
		assertEquals("step.started", received.get(0).type());
		assertEquals("step.completed", received.get(1).type());
	}

	@Test
	public void testStreamingListenerToolCallArgsFallback() {
		List<AgentEvent> received = new ArrayList<>();
		StreamingAgentListener listener = new StreamingAgentListener("agent", received::add);
		// 空白 arguments → 空对象
		listener.onToolCall(ToolCall.of("c1", "lookup", "   "));
		// 非法 JSON → catch 兜底为空对象
		listener.onToolCall(ToolCall.of("c2", "lookup", "{'unclosed"));
		assertEquals(2, received.size());
		assertTrue(received.get(0) instanceof ToolCalledEvent);
		assertTrue(received.get(1) instanceof ToolCalledEvent);
	}

	@Test
	public void testIsToolErrorNullAndErrorPrefixes() {
		// null 结果不是错误
		assertFalse(StreamingAgentListener.isToolError(null));
		assertFalse(StreamingAgentListener.isToolError("正常结果"));
		// 各错误前缀判定
		assertTrue(StreamingAgentListener.isToolError("参数校验失败: x"));
		assertTrue(StreamingAgentListener.isToolError("tool not found: x"));
		assertTrue(StreamingAgentListener.isToolError("工具执行异常 x"));
		assertTrue(StreamingAgentListener.isToolError("arguments 不是合法 JSON 对象"));
	}

	@Test
	public void testEventRecordTypes() {
		assertEquals("step.started",
				new StepStartedEvent("a", 1, "d", 1L).type());
		assertEquals("step.completed",
				new StepCompletedEvent("a", 1, "r", 1L).type());
		assertEquals("token.delta",
				new TokenDeltaEvent("a", "你好", 1L).type());
	}
}
