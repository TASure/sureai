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

package com.sure.ai.agent.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.model.ChatMessage;

/**
 * {@link InMemoryConversationMemory} 与 {@link AgentListener} 默认方法补充测试。
 */
public class MemoryAndListenerEdgeTest {

	@Test
	public void testMemoryToStringReflectsHistory() {
		InMemoryConversationMemory memory = new InMemoryConversationMemory();
		memory.add(ChatMessage.user("你好"));
		String text = memory.toString();
		assertTrue(text, text.startsWith("["));
		assertEquals(1, memory.size());
	}

	@Test
	public void testListenerDefaultNoOpMethodsAreSafe() {
		AgentListener listener = new AgentListener() {
		};
		// 默认空实现不应抛异常
		listener.onError(new RuntimeException("x"));
		listener.onThought("t");
		listener.onStepStart(0, "s");
		listener.onStepComplete(0, "r");
		listener.onFinish("f");
		listener.onPlanGenerated(java.util.List.of("s"));
		listener.onToolCall(com.sure.ai.model.ToolCall.of("c", "t", "{}"));
		listener.onToolResult(com.sure.ai.model.ToolCall.of("c", "t", "{}"), "r");
	}
}
