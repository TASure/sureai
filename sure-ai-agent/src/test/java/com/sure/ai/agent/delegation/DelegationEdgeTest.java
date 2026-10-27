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
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;

import com.sure.ai.agent.plan.PlanExecuteAgent;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolCall;
import org.junit.Test;

/**
 * 委派边界与组合测试：PlanExecute 父/子组合、备选任务参数、空参数、空答案归一。
 */
public class DelegationEdgeTest {

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
	public void planExecuteParentDelegatesToLeafChild() {
		AgentRunner leaf = AgentRunner.of("leaf", "叶子", t -> "子Agent产出");
		ToolRegistry reg = new ToolRegistry();
		AgentAsTool.of("sub_a", "子Agent", ChildAgentFactory.fixed(leaf)).register(reg);

		String plan = "[{\"step\":\"交给子Agent处理\"}]";
		ScriptedClient client = new ScriptedClient()
			.withText(plan)
			.withToolCalls(List.of(call("s1", "sub_a", "{\"task\":\"子任务\"}")))
			.withText("步骤拿到子产出")
			.withText("最终综合答案");

		PlanExecuteAgent parent = new PlanExecuteAgent(client, baseRequest(), reg,
			null, 10, Duration.ofSeconds(30));
		assertEquals("最终综合答案", parent.run("父任务"));
	}

	@Test
	public void planExecuteAgentCanBeWrappedAsRunner() throws Exception {
		// PlanExecute 作为被委派子 Agent（空注册表，单步直答）
		ScriptedClient client = new ScriptedClient()
			.withText("[{\"step\":\"直接回答\"}]")
			.withText("步骤结果")
			.withText("PlanExecute 最终答案");
		PlanExecuteAgent planAgent = new PlanExecuteAgent(client, baseRequest(),
			new ToolRegistry(), null, 10, Duration.ofSeconds(30));
		AgentRunner runner = AgentRunner.of(planAgent, "planner", "规划子");

		ToolRegistry reg = new ToolRegistry();
		AgentAsTool.of("planner", "规划子", ChildAgentFactory.fixed(runner))
			.register(reg);
		String out = reg.getHandler("planner").orElseThrow()
			.execute(Json.object());
		assertTrue(out.contains("PlanExecute 最终答案"));
	}

	@Test
	public void altTaskParameterKeysAccepted() throws Exception {
		AgentAsTool tool = AgentAsTool.of("x", "x",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> t)));

		JsonObject desc = Json.object();
		desc.set("description", "按description传任务");
		assertEquals("按description传任务", tool.toHandler().execute(desc));

		JsonObject query = Json.object();
		query.set("query", "按query传任务");
		assertEquals("按query传任务", tool.toHandler().execute(query));

		JsonObject input = Json.object();
		input.set("input", "按input传任务");
		assertEquals("按input传任务", tool.toHandler().execute(input));
	}

	@Test
	public void nullArgumentsAndBlankTaskHandled() throws Exception {
		AgentAsTool tool = AgentAsTool.of("x", "x",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> "收到[" + t + "]")));
		assertEquals("收到[]", tool.toHandler().execute(null));

		JsonObject blank = Json.object();
		blank.set("task", "   ");
		assertEquals("收到[]", tool.toHandler().execute(blank));
	}

	@Test
	public void nullChildAnswerNormalizedToEmpty() throws Exception {
		AgentAsTool tool = AgentAsTool.of("x", "x",
			ChildAgentFactory.fixed(AgentRunner.of("leaf", "叶子", t -> null)));
		assertEquals("", tool.toHandler().execute(Json.object()));
	}

	@Test
	public void reactAgentAdapterExposesIdAndDescription() {
		ScriptedClient client = new ScriptedClient().withText("直答");
		ReActAgent agent = new ReActAgent(client, baseRequest(), new ToolRegistry());
		AgentRunner runner = AgentRunner.of(agent, "react1", "ReAct子");
		assertEquals("react1", runner.id());
		assertEquals("ReAct子", runner.description());
	}
}
