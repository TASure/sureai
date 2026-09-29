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

package com.sure.ai.agent.checkpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.sure.ai.agent.memory.InMemoryConversationMemory;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 检查点持久化能力单元测试。
 */
public class CheckpointTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final String WEATHER_SCHEMA = """
			{"type":"object","required":["city"],
			 "properties":{"city":{"type":"string"}}}
			""";

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
	public void testCreateInitialCheckpoint() {
		ToolRegistry registry = new ToolRegistry();
		RecordingClient client = new RecordingClient().withText("答案");
		InMemoryConversationMemory memory = new InMemoryConversationMemory(10);
		memory.add(ChatMessage.user("历史问题"));
		memory.add(ChatMessage.assistant("历史答案"));

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
			null, 10, Duration.ofSeconds(30), memory);
		AgentCheckpoint cp = AgentCheckpointer.create("sess-1", agent, "新问题");

		List<ChatMessage> h = cp.history();
		// base(system) + memory(2) + user(1) = 4
		assertEquals(4, h.size());
		assertEquals("你是助手", h.get(0).content());
		assertEquals("历史问题", h.get(1).content());
		assertEquals("历史答案", h.get(2).content());
		assertEquals("新问题", h.get(3).content());
		assertEquals(0, cp.iteration());
		assertNull(cp.finalAnswer());
		assertEquals("sess-1", cp.sessionId());
	}

	@Test
	public void testSaveAndLoadRoundTrip() {
		InMemoryCheckpointStore store = new InMemoryCheckpointStore();
		List<ChatMessage> history = List.of(
			ChatMessage.system("sys"),
			ChatMessage.user("天气"),
			ChatMessage.assistant(List.of(call("c1", "get_weather", "{\"city\":\"西安\"}"))),
			ChatMessage.tool("c1", "晴"));
		JsonObject meta = Json.object();
		meta.put("k", "v");
		AgentCheckpoint cp = AgentCheckpoint.builder()
			.sessionId("sess-rt")
			.history(history)
			.iteration(1)
			.finalAnswer("西安晴")
			.createdAtEpochMs(123456L)
			.metadata(meta)
			.build();
		store.save(cp);

		Optional<AgentCheckpoint> loaded = store.load("sess-rt");
		assertTrue(loaded.isPresent());
		AgentCheckpoint l = loaded.get();
		assertEquals("sess-rt", l.sessionId());
		assertEquals(1, l.iteration());
		assertEquals("西安晴", l.finalAnswer());
		assertEquals(123456L, l.createdAtEpochMs());
		assertEquals(4, l.history().size());
		// 历史含 toolCalls 的助手消息应完整还原
		assertEquals("assistant", l.history().get(2).role().value());
		assertNotNull(l.history().get(2).toolCalls());
		assertEquals("get_weather", l.history().get(2).toolCalls().get(0).name());
		assertEquals("c1", l.history().get(3).toolCallId());
		assertEquals("v", l.metadata().optString("k", null));
	}

	@Test
	public void testFileStoreRoundTrip() throws Exception {
		Path dir = folder.getRoot().toPath();
		FileCheckpointStore store = new FileCheckpointStore(dir);

		AgentCheckpoint cp = AgentCheckpoint.builder()
			.sessionId("sess-file")
			.history(List.of(ChatMessage.user("hi")))
			.iteration(0)
			.finalAnswer("hello")
			.build();
		store.save(cp);

		Path file = dir.resolve("sess-file.json");
		assertTrue(Files.exists(file));

		Optional<AgentCheckpoint> loaded = store.load("sess-file");
		assertTrue(loaded.isPresent());
		assertEquals("hello", loaded.get().finalAnswer());
		assertEquals(1, store.listSessions().size());
		assertEquals("sess-file", store.listSessions().get(0));
	}

	@Test
	public void testFileStoreDelete() throws Exception {
		FileCheckpointStore store = new FileCheckpointStore(folder.getRoot().toPath());
		store.save(AgentCheckpoint.builder().sessionId("d1")
			.history(List.of()).build());
		assertTrue(store.load("d1").isPresent());
		store.delete("d1");
		assertFalse(store.load("d1").isPresent());
		assertTrue(store.listSessions().isEmpty());
	}

	@Test
	public void testFileStorePathTraversal() throws Exception {
		Path dir = folder.getRoot().toPath();
		FileCheckpointStore store = new FileCheckpointStore(dir);

		// sessionId 含 ../ 不应写出目录外
		store.save(AgentCheckpoint.builder().sessionId("../evil")
			.history(List.of(ChatMessage.user("x"))).build());

		// 目录内只应有一个被清洗后的文件
		assertEquals(1, store.listSessions().size());
		// 父目录不应出现 evil.json
		assertFalse(Files.exists(dir.getParent().resolve("evil.json")));
		// 不抛异常且能按同名回读（清洗规则一致）
		assertTrue(store.load("../evil").isPresent());
	}

	@Test
	public void testSerializerRoundTrip() {
		List<ChatMessage> history = List.of(
			ChatMessage.system("sys"),
			ChatMessage.user("问"),
			ChatMessage.assistant(List.of(call("c1", "f", "{\"a\":1}"))),
			ChatMessage.tool("c1", "结果"),
			ChatMessage.assistant("最终"));
		AgentCheckpoint cp = AgentCheckpoint.builder()
			.sessionId("ser")
			.history(history)
			.iteration(2)
			.finalAnswer("最终")
			.createdAtEpochMs(999L)
			.build();

		String json = CheckpointSerializer.toJson(cp);
		AgentCheckpoint back = CheckpointSerializer.fromJson(json);

		assertEquals("ser", back.sessionId());
		assertEquals(2, back.iteration());
		assertEquals("最终", back.finalAnswer());
		assertEquals(999L, back.createdAtEpochMs());
		assertEquals(5, back.history().size());
		assertEquals("system", back.history().get(0).role().value());
		assertEquals("assistant", back.history().get(2).role().value());
		assertEquals("f", back.history().get(2).toolCalls().get(0).name());
		assertEquals("{\"a\":1}", back.history().get(2).toolCalls().get(0).argumentsJson());
		assertEquals("c1", back.history().get(3).toolCallId());
		assertEquals("结果", back.history().get(3).content());
	}

	@Test
	public void testResumeWithHistory() {
		// 构造一个带历史的检查点：base(system) + 一轮已完成的工具调用
		List<ChatMessage> history = new ArrayList<>();
		history.add(ChatMessage.system("你是助手"));
		history.add(ChatMessage.user("西安天气"));
		history.add(ChatMessage.assistant(List.of(call("c1", "get_weather", "{\"city\":\"西安\"}"))));
		history.add(ChatMessage.tool("c1", "晴 26℃"));
		history.add(ChatMessage.assistant("西安晴 26℃"));
		AgentCheckpoint cp = AgentCheckpoint.builder()
			.sessionId("resume-1")
			.history(history)
			.iteration(1)
			.finalAnswer("西安晴 26℃")
			.build();

		InMemoryCheckpointStore store = new InMemoryCheckpointStore();
		store.save(cp);

		// 模板 agent：空 registry → resume 后单次 chat
		RecordingClient client = new RecordingClient().withText("继续作答");
		ReActAgent agent = new ReActAgent(client, baseRequest(), new ToolRegistry());

		String answer = AgentCheckpointer.resume(store, "resume-1", agent);
		assertEquals("继续作答", answer);

		// 模型应看到完整历史：system + 4 条尾部（用户消息/工具调用/工具结果/助手）
		List<ChatMessage> sent = client.lastRequest().messages();
		assertEquals(5, sent.size());
		assertEquals("你是助手", sent.get(0).content());
		assertEquals("西安天气", sent.get(1).content());
		assertNotNull(sent.get(2).toolCalls());
		assertEquals("c1", sent.get(3).toolCallId());
		assertEquals("西安晴 26℃", sent.get(4).content());
	}

	@Test
	public void testAgentAutoCheckpoint() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("get_weather", "查天气", WEATHER_SCHEMA),
			args -> "晴 26℃");

		RecordingClient client = new RecordingClient()
			.withToolCalls(List.of(call("c1", "get_weather", "{\"city\":\"西安\"}")))
			.withText("西安今天晴，26℃。");

		InMemoryCheckpointStore store = new InMemoryCheckpointStore();
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
			null, 10, Duration.ofSeconds(30), null, store, "auto-1");

		String answer = agent.run("西安天气？");
		assertEquals("西安今天晴，26℃。", answer);

		Optional<AgentCheckpoint> loaded = store.load("auto-1");
		assertTrue(loaded.isPresent());
		AgentCheckpoint cp = loaded.get();
		assertEquals("西安今天晴，26℃。", cp.finalAnswer());
		// 完成了一轮工具迭代
		assertEquals(1, cp.iteration());
		// 历史末尾应包含已执行的工具结果（runLoop 在模型给出文本答案时不追加助手消息）
		ChatMessage last = cp.history().get(cp.history().size() - 1);
		assertEquals("tool", last.role().value());
		assertEquals("晴 26℃", last.content());
	}

	/** 记录请求、按序返回预设响应的测试客户端。 */
	static final class RecordingClient implements AiClient {

		private final List<ChatResponse> responses = new ArrayList<>();
		private final List<ChatRequest> requests = new ArrayList<>();
		private int idx;

		RecordingClient withText(String text) {
			this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(text), "stop")),
				TokenUsage.of(1, 1, 2), null));
			return this;
		}

		RecordingClient withToolCalls(List<ToolCall> calls) {
			this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(calls), "tool_calls")),
				TokenUsage.of(1, 1, 2), null));
			return this;
		}

		ChatRequest lastRequest() {
			return this.requests.get(this.requests.size() - 1);
		}

		@Override
		public String name() {
			return "recording";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.requests.add(request);
			int i = Math.min(this.idx, this.responses.size() - 1);
			if (this.idx < this.responses.size()) {
				this.idx++;
			}
			return this.responses.get(i);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
			// 不使用流式
		}

		@Override
		public void close() {
			// no-op
		}
	}
}
