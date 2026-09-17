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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;

import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import org.junit.Test;

/**
 * {@link PlanExecuteAgent} 骨架单元测试：确认 run 抛未实现异常。
 */
public class PlanExecuteAgentTest {

	@Test
	public void testRunUnsupported() {
		ChatRequest base = ChatRequest.builder()
			.model("m")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
		PlanExecuteAgent agent = new PlanExecuteAgent(null, base, new ToolRegistry());
		UnsupportedOperationException ex =
				assertThrows(UnsupportedOperationException.class, () -> agent.run("do it"));
		assertTrue(ex.getMessage().contains("ReActAgent"));
	}

	@Test
	public void testFullConstructorAccepted() {
		ChatRequest base = ChatRequest.builder()
			.model("m")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
		PlanExecuteAgent agent = new PlanExecuteAgent(null, base, new ToolRegistry(),
				null, 5, Duration.ofSeconds(10));
		assertTrue(agent != null);
	}
}
