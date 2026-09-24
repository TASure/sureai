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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.memory.ConversationMemory;
import com.sure.ai.agent.memory.InMemoryConversationMemory;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import org.junit.Test;

/**
 * {@link ReActAgent} 单元测试（FakeAiClient 零真实网络）。
 */
public class ReActAgentTest {

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
	public void testSingleToolCall() {
		ToolRegistry registry = new ToolRegistry();
		List<String> seenArgs = new ArrayList<>();
		registry.register(ToolFunction.of("get_weather", "查天气", WEATHER_SCHEMA),
				args -> {
					seenArgs.add(args.getString("city"));
					return "晴 26℃";
				});

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "get_weather", "{\"city\":\"西安\"}")))
			.withText("西安今天晴，26℃。");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("西安天气如何？");

		assertEquals("西安今天晴，26℃。", answer);
		assertEquals(List.of("西安"), seenArgs);
		// 两轮请求：第一轮带 tool_calls，第二轮带 tool 结果
		assertEquals(2, client.requests().size());
	}

	@Test
	public void testMultipleToolCalls() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("get_weather", "查天气", WEATHER_SCHEMA),
				args -> "晴");
		registry.register(ToolFunction.of("calculate", "计算",
				"{\"type\":\"object\",\"properties\":{\"expr\":{\"type\":\"string\"}}}"),
				args -> "4");

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(
					call("c1", "get_weather", "{\"city\":\"西安\"}"),
					call("c2", "calculate", "{\"expr\":\"1+3\"}")))
			.withText("综合：晴，4。");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("综合一下");
		assertEquals("综合：晴，4。", answer);
		assertEquals(2, client.requests().size());
	}

	@Test
	public void testToolNotFound() {
		ToolRegistry registry = new ToolRegistry();
		// 注册一个无关工具，使注册中心非空从而进入循环；模型调用未注册的 ghost
		registry.register(ToolFunction.of("other", "其他", "{}"), args -> "x");
		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "ghost", "{}")))
			.withText("工具不存在，我直接回答。");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("调一下 ghost");
		assertEquals("工具不存在，我直接回答。", answer);

		// 第二轮请求历史里应包含 tool 角色消息
		ChatRequest second = client.requests().get(1);
		boolean hasToolRole = second.messages().stream().anyMatch(m -> m.toolCallId() != null);
		assertTrue(hasToolRole);
	}

	@Test
	public void testToolThrowsException() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("boom", "抛异常", "{}"), args -> {
			throw new IllegalStateException("炸了");
		});

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "boom", "{}")))
			.withText("工具出错后我重新作答。");

		AtomicInteger errors = new AtomicInteger();
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry, new AgentListener() {
			@Override
			public void onError(Throwable e) {
				errors.incrementAndGet();
			}
		}, 10, Duration.ofSeconds(30));

		String answer = agent.run("触发");
		assertEquals("工具出错后我重新作答。", answer);
		assertEquals(1, errors.get());
	}

	@Test
	public void testValidationFailure() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("get_weather", "查天气", WEATHER_SCHEMA),
				args -> "不应被调用");

		FakeAiClient client = new FakeAiClient()
			// 缺少必填 city
			.withToolCalls(List.of(call("c1", "get_weather", "{}")))
			.withText("参数错了，我修正后回答：晴。");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("天气");
		assertEquals("参数错了，我修正后回答：晴。", answer);
	}

	@Test
	public void testMaxIterationsExceeded() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("loop", "循环", "{}"), args -> "一直调");

		// 始终返回 tool_calls
		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "loop", "{}")))
			.withToolCalls(List.of(call("c2", "loop", "{}")))
			.withToolCalls(List.of(call("c3", "loop", "{}")));

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry, null, 2, Duration.ofSeconds(30));
		AiException ex = assertThrows(AiException.class, () -> agent.run("死循环"));
		assertTrue(ex.getMessage().contains("max iterations"));
	}

	@Test
	public void testNoToolsDirectAnswer() {
		ToolRegistry registry = new ToolRegistry(); // 空
		FakeAiClient client = new FakeAiClient()
			.withText("没有工具，直接回答。");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("你好");
		assertEquals("没有工具，直接回答。", answer);
		assertEquals(1, client.requests().size());
		assertTrue(registry.isEmpty());
	}

	@Test
	public void testListenerCallbacks() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("get_weather", "查天气", WEATHER_SCHEMA),
				args -> "晴");

		List<String> events = new ArrayList<>();
		AgentListener listener = new AgentListener() {
			@Override
			public void onToolCall(ToolCall toolCall) {
				events.add("call:" + toolCall.name());
			}

			@Override
			public void onToolResult(ToolCall toolCall, String result) {
				events.add("result:" + result);
			}

			@Override
			public void onFinish(String finalAnswer) {
				events.add("finish:" + finalAnswer);
			}
		};

		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "get_weather", "{\"city\":\"西安\"}")))
			.withText("完成");

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry, listener, 10, Duration.ofSeconds(30));
		String answer = agent.run("天气");
		assertEquals("完成", answer);
		assertTrue(events.contains("call:get_weather"));
		assertTrue(events.contains("result:晴"));
		assertTrue(events.contains("finish:完成"));
	}

	@Test
	public void testRunWithBaseMessagesOnly() {
		ToolRegistry registry = new ToolRegistry();
		FakeAiClient client = new FakeAiClient().withText("用已有消息回答");
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run();
		assertEquals("用已有消息回答", answer);
		assertNotNull(client.lastRequest());
	}

	@Test
	public void testInvalidArgumentsJsonEchoedBack() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("f", "f", "{}"), args -> "不该执行");
		FakeAiClient client = new FakeAiClient()
			.withToolCalls(List.of(call("c1", "f", "not-json")))
			.withText("参数非法，我重新答。");
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("x");
		assertEquals("参数非法，我重新答。", answer);
	}

	@Test
	public void testMemoryInjectedIntoRequest() {
		ToolRegistry registry = new ToolRegistry(); // 空：单次 chat
		ConversationMemory memory = new InMemoryConversationMemory(10);
		memory.add(ChatMessage.user("上一轮问题"));
		memory.add(ChatMessage.assistant("上一轮答案"));

		FakeAiClient client = new FakeAiClient().withText("这一轮答案");
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30), memory);
		String answer = agent.run("这一轮问题");
		assertEquals("这一轮答案", answer);

		// 请求消息顺序：base(system) → memory 历史 → 当前 user
		List<ChatMessage> msgs = client.lastRequest().messages();
		assertEquals(4, msgs.size());
		assertEquals("你是助手", msgs.get(0).content());
		assertEquals("上一轮问题", msgs.get(1).content());
		assertEquals("上一轮答案", msgs.get(2).content());
		assertEquals("这一轮问题", msgs.get(3).content());
	}

	@Test
	public void testMemoryRecordsAfterRun() {
		ToolRegistry registry = new ToolRegistry();
		ConversationMemory memory = new InMemoryConversationMemory(10);

		FakeAiClient client = new FakeAiClient().withText("助手答案");
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
				null, 10, Duration.ofSeconds(30), memory);
		agent.run("用户问题");

		assertEquals(2, memory.size());
		assertEquals("用户问题", memory.history().get(0).content());
		assertEquals("user", memory.history().get(0).role().name().toLowerCase());
		assertEquals("助手答案", memory.history().get(1).content());
		assertEquals("assistant", memory.history().get(1).role().name().toLowerCase());
	}

	@Test
	public void testNoMemoryBackwardCompatible() {
		ToolRegistry registry = new ToolRegistry();
		FakeAiClient client = new FakeAiClient().withText("无记忆答案");
		// 旧构造器（不带 memory）行为不变
		ReActAgent agent = new ReActAgent(client, baseRequest(), registry);
		String answer = agent.run("你好");
		assertEquals("无记忆答案", answer);

		List<ChatMessage> msgs = client.lastRequest().messages();
		// base(system) + 当前 user = 2 条，无额外历史
		assertEquals(2, msgs.size());
		assertEquals("你好", msgs.get(1).content());
	}
}
