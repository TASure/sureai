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

package com.sure.ai.agent.skill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 技能工具与 {@link ReActAgent} 组合测试：脚本化假客户端，零真实网络。
 */
public class SkillReActAgentTest {

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	/** 脚本化假客户端：按序返回预设响应并记录请求。 */
	static final class ScriptedClient implements AiClient {
		private final List<ChatResponse> responses = new ArrayList<>();
		private final List<ChatRequest> requests = new ArrayList<>();
		private int idx;

		ScriptedClient withToolCalls(List<ToolCall> calls) {
			this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
					List.of(Choice.of(0, ChatMessage.assistant(calls), "tool_calls")),
					TokenUsage.of(1, 1, 2), null));
			return this;
		}

		ScriptedClient withText(String text) {
			this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
					List.of(Choice.of(0, ChatMessage.assistant(text), "stop")),
					TokenUsage.of(1, 1, 2), null));
			return this;
		}

		List<ChatRequest> requests() {
			return this.requests;
		}

		@Override
		public String name() {
			return "scripted";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.requests.add(request);
			return this.responses.get(Math.min(this.idx++, this.responses.size() - 1));
		}

		@Override
		public void chatStream(ChatRequest request, java.util.function.Consumer<com.sure.ai.model.ChatStreamChunk> consumer) {
			// 测试不使用流式
		}

		@Override
		public void close() {
			// no-op
		}
	}

	private Skill writeLocalSkill() throws Exception {
		Path root = this.temp.newFolder("skills").toPath();
		Path dir = root.resolve("alpha");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("SKILL.md"), """
				---
				name: alpha-skill
				description: Alpha capability. Use when alpha is requested.
				---
				# Alpha Body
				Follow alpha instructions: verify then summarize.
				""", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("references"));
		Files.writeString(dir.resolve("references/guide.md"), "ALPHA REFERENCE TEXT",
				StandardCharsets.UTF_8);
		return SkillLoader.load(root).get(0);
	}

	private static ChatRequest baseRequest() {
		return ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.system("你是助手")))
			.build();
	}

	@Test
	public void testAgentLoadsSkillBodyThenAnswers() throws Exception {
		Skill skill = writeLocalSkill();
		ToolRegistry registry = new ToolRegistry();
		SkillTool.register(skill, registry);

		ScriptedClient client = new ScriptedClient()
			.withToolCalls(List.of(ToolCall.of("c1", "alpha-skill", "{}")))
			.withText("已按 Alpha 指导完成：总结完毕。");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("处理 alpha 任务");

		assertEquals("已按 Alpha 指导完成：总结完毕。", answer);
		assertEquals(2, client.requests().size());
		// 第一轮请求已携带技能工具声明
		assertTrue(client.requests().get(0).tools().stream()
				.anyMatch(t -> t.function().name().equals("alpha-skill")));
		// 第二轮历史包含工具回灌的正文
		String history = client.requests().get(1).messages().stream()
				.map(m -> m.content() == null ? "" : m.content())
				.reduce("", String::concat);
		assertTrue(history.contains("Follow alpha instructions"));
	}

	@Test
	public void testAgentReadsSkillResource() throws Exception {
		Skill skill = writeLocalSkill();
		ToolRegistry registry = new ToolRegistry();
		SkillTool.register(skill, registry);

		ScriptedClient client = new ScriptedClient()
			.withToolCalls(List.of(ToolCall.of("c1", "alpha-skill",
					"{\"resource\":\"references/guide.md\"}")))
			.withText("已查阅参考后作答。");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("查 alpha 参考");
		assertEquals("已查阅参考后作答。", answer);

		String history = client.requests().get(1).messages().stream()
				.map(m -> m.content() == null ? "" : m.content())
				.reduce("", String::concat);
		assertTrue(history.contains("ALPHA REFERENCE TEXT"));
	}
}
