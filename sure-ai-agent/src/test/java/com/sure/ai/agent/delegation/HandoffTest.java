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
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;

import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolCall;
import org.junit.Test;

/**
 * {@link HandoffAgent} / {@link HandoffTool} 与 AgentAsTool 语义差异测试。
 */
public class HandoffTest {

	private static ChatRequest baseRequest() {
		return ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.system("你是助手")))
			.build();
	}

	private static ToolCall call(String id, String name, String args) {
		return new ToolCall(id, name, args);
	}

	@Test
	public void handoffAgentDelegatesAndExposesTarget() throws Exception {
		AgentRunner target = AgentRunner.of("writer", "写作目标", t -> "目标答案");
		HandoffAgent handoff = HandoffAgent.of(target, "h1", "交给写作目标");
		assertSame(target, handoff.target());
		assertEquals("h1", handoff.id());
		assertEquals("交给写作目标", handoff.description());
		assertEquals("目标答案", handoff.run("写一段话"));
	}

	@Test
	public void handoffToolResultCarriesTerminalMarker() throws Exception {
		AgentRunner target = AgentRunner.of("writer", "写作目标", t -> "目标最终答案");
		String result = HandoffTool.toHandler(target).execute(Json.object());
		assertTrue(result.startsWith(HandoffTool.HANDOFF_PREFIX));
		assertTrue(result.contains("writer"));
		assertTrue(result.contains("目标最终答案"));
	}

	@Test
	public void handoffRegisteredToolReturnsMarkerWhileAgentAsToolDoesNot() throws Exception {
		AgentRunner target = AgentRunner.of("writer", "写作目标", t -> "目标最终答案");

		ToolRegistry handoffReg = new ToolRegistry();
		HandoffTool.register(target, handoffReg, "交接给写作目标");
		String handoffOut = handoffReg.getHandler("writer").orElseThrow()
			.execute(Json.object());
		assertTrue(handoffOut.startsWith(HandoffTool.HANDOFF_PREFIX));

		// 对照：AgentAsTool 普通委派原样返回子答案，无交接标记
		ToolRegistry asToolReg = new ToolRegistry();
		AgentAsTool.of("writer", "写作子", ChildAgentFactory.fixed(target))
			.register(asToolReg);
		String asToolOut = asToolReg.getHandler("writer").orElseThrow()
			.execute(Json.object());
		assertEquals("目标最终答案", asToolOut);
		assertTrue(!asToolOut.startsWith(HandoffTool.HANDOFF_PREFIX));
	}

	@Test
	public void handoffParentStopsAfterReceivingHandedOffAnswer() {
		AgentRunner target = AgentRunner.of("writer", "写作目标", t -> "目标最终答案");
		ToolRegistry reg = new ToolRegistry();
		HandoffTool.register(target, reg, "交接给写作目标");

		// 父模型调用交接工具后直接给出最终答案（不再继续调用其他工具）
		ScriptedClient client = new ScriptedClient()
			.withToolCalls(List.of(call("c1", "writer", "{\"task\":\"写文章\"}")))
			.withText("已交接，以目标答案为准。");
		ReActAgent parent = new ReActAgent(client, baseRequest(), reg,
			null, 10, Duration.ofSeconds(10));

		assertEquals("已交接，以目标答案为准。", parent.run("写文章任务"));
		assertEquals(2, client.requests().size());
	}
}
