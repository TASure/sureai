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
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
import org.junit.Test;

/**
 * 流式事件能力单元测试。
 */
public class EventTest {

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

	@Test
	public void testPublisherSubscribeAndPublish() {
		AgentEventPublisher publisher = new AgentEventPublisher();
		List<AgentEvent> received = new CopyOnWriteArrayList<>();
		AgentEventSink sink = received::add;

		publisher.subscribe(sink);
		publisher.publish(new ThoughtEvent("a", "t1", 1L));
		publisher.publish(new ThoughtEvent("a", "t2", 2L));
		assertEquals(2, received.size());

		publisher.unsubscribe(sink);
		publisher.publish(new ThoughtEvent("a", "t3", 3L));
		assertEquals(2, received.size());
	}

	@Test
	public void testPublisherThreadSafety() throws Exception {
		AgentEventPublisher publisher = new AgentEventPublisher();
		AtomicInteger count = new AtomicInteger();
		publisher.subscribe(e -> count.incrementAndGet());

		int n = 100;
		CountDownLatch done = new CountDownLatch(n);
		for (int i = 0; i < n; i++) {
			final int k = i;
			Thread t = new Thread(() -> {
				publisher.publish(new ThoughtEvent("a", "t" + k, System.currentTimeMillis()));
				done.countDown();
			});
			t.start();
		}
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(n, count.get());
	}

	@Test
	public void testStreamingListenerMapsThought() {
		List<AgentEvent> received = new ArrayList<>();
		StreamingAgentListener listener = new StreamingAgentListener("agent-1", received::add);
		listener.onThought("正在思考");
		assertEquals(1, received.size());
		ThoughtEvent e = (ThoughtEvent) received.get(0);
		assertEquals("thought", e.type());
		assertEquals("agent-1", e.agentId());
		assertEquals("正在思考", e.thought());
		assertTrue(e.timestampEpochMs() > 0);
	}

	@Test
	public void testStreamingListenerMapsToolCall() {
		List<AgentEvent> received = new ArrayList<>();
		StreamingAgentListener listener = new StreamingAgentListener("a1", received::add);
		listener.onToolCall(new ToolCall("c1", "get_weather", "{\"city\":\"西安\",\"n\":3}"));
		assertEquals(1, received.size());
		ToolCalledEvent e = (ToolCalledEvent) received.get(0);
		assertEquals("tool.called", e.type());
		assertEquals("get_weather", e.toolName());
		assertEquals("西安", e.arguments().getString("city"));
		assertEquals(3, e.arguments().getInt("n"));
	}

	@Test
	public void testStreamingListenerMapsFinish() {
		List<AgentEvent> received = new ArrayList<>();
		StreamingAgentListener listener = new StreamingAgentListener("a1", received::add);
		listener.onFinish("最终答案");
		FinalAnswerEvent e = (FinalAnswerEvent) received.get(0);
		assertEquals("final.answer", e.type());
		assertEquals("最终答案", e.answer());
	}

	@Test
	public void testStreamingListenerMapsError() {
		List<AgentEvent> received = new ArrayList<>();
		StreamingAgentListener listener = new StreamingAgentListener("a1", received::add);
		listener.onError(new IllegalStateException("boom"));
		AgentErrorEvent e = (AgentErrorEvent) received.get(0);
		assertEquals("agent.error", e.type());
		assertEquals("IllegalStateException", e.error());
		assertEquals("boom", e.message());
	}

	@Test
	public void testSseWriterFormat() {
		ThoughtEvent event = new ThoughtEvent("a1", "hi", 123L);
		String sse = AgentEventSseWriter.toSse(event);

		assertTrue(sse.startsWith("event: thought\n"));
		assertTrue(sse.contains("data: "));
		assertTrue(sse.endsWith("\n\n"));

		// 提取 data 行并校验为可解析 JSON
		String dataLine = sse.lines()
			.filter(l -> l.startsWith("data: "))
			.findFirst()
			.orElseThrow();
		String json = dataLine.substring("data: ".length());
		JsonObject obj = Json.parse(json).getAsJsonObject();
		assertEquals("thought", obj.getString("type"));
		assertEquals("hi", obj.getString("thought"));
		assertEquals(123L, obj.optLong("timestampEpochMs", 0L));

		assertEquals("data: [DONE]\n\n", AgentEventSseWriter.doneMarker());
	}

	@Test
	public void testEndToEndAgentEvents() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("get_weather", "查天气", WEATHER_SCHEMA),
			args -> "晴 26℃");

		StubClient client = new StubClient()
			.withToolCalls(List.of(new ToolCall("c1", "get_weather", "{\"city\":\"西安\"}")))
			.withText("西安晴 26℃。");

		AgentEventPublisher publisher = new AgentEventPublisher();
		List<AgentEvent> received = new CopyOnWriteArrayList<>();
		publisher.subscribe(received::add);

		ReActAgent agent = new ReActAgent(client, baseRequest(), registry,
			new StreamingAgentListener("react-1", publisher), 10, Duration.ofSeconds(30));
		String answer = agent.run("西安天气？");
		assertEquals("西安晴 26℃。", answer);

		// 顺序：tool.called → tool.completed → final.answer
		assertEquals(3, received.size());
		assertEquals("tool.called", received.get(0).type());
		assertEquals("tool.completed", received.get(1).type());
		assertEquals("final.answer", received.get(2).type());

		ToolCompletedEvent completed = (ToolCompletedEvent) received.get(1);
		assertEquals("get_weather", completed.toolName());
		assertTrue(completed.success());
		assertEquals("晴 26℃", completed.result());

		FinalAnswerEvent fin = (FinalAnswerEvent) received.get(2);
		assertEquals("西安晴 26℃。", fin.answer());
	}

	/** 记录请求、按序返回预设响应的测试客户端。 */
	static final class StubClient implements AiClient {

		private final List<ChatResponse> responses = new ArrayList<>();
		private int idx;

		StubClient withText(String text) {
			this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(text), "stop")),
				TokenUsage.of(1, 1, 2), null));
			return this;
		}

		StubClient withToolCalls(List<ToolCall> calls) {
			this.responses.add(ChatResponse.of("r" + this.responses.size(), "fake-model",
				List.of(Choice.of(0, ChatMessage.assistant(calls), "tool_calls")),
				TokenUsage.of(1, 1, 2), null));
			return this;
		}

		@Override
		public String name() {
			return "stub";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
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
