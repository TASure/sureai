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

package com.sure.ai.evals.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.evals.EvalException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.Role;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;

/**
 * {@link EvalTrace} JSON 往返与解析边界。
 */
public class EvalTraceTest {

	private static ChatResponse sampleResponse() {
		ChatMessage assistant = ChatMessage.assistant(List.of(
			ToolCall.of("call-1", "get_weather", "{\"city\":\"西安\"}")));
		return ChatResponse.of("resp-1", "test-model",
			List.of(Choice.of(0, assistant, "tool_calls")),
			TokenUsage.of(2, 3, 5), null);
	}

	private static EvalTrace sampleTrace() {
		ChatRequest request = ChatRequest.builder()
			.model("test-model")
			.temperature(0.7)
			.maxTokens(64)
			.topP(0.9)
			.stream(false)
			.messages(
				ChatMessage.system("sys指令"),
				ChatMessage.user("西安天气如何？"),
				ChatMessage.tool("call-1", "晴天 20℃"))
			.build();
		return new EvalTrace("case-1", 1700000000000L, request, sampleResponse());
	}

	@Test
	public void roundTrip保留关键字段() {
		EvalTrace trace = sampleTrace();
		String json = EvalTrace.toJson(trace);
		assertNotNull(json);
		assertEquals(json, EvalTrace.toJsonLine(trace));

		EvalTrace parsed = EvalTrace.parse(json);
		assertEquals("case-1", parsed.id());
		assertEquals(1700000000000L, parsed.recordedAtMillis());
		// 请求侧
		assertEquals("test-model", parsed.request().model());
		assertEquals(Double.valueOf(0.7), parsed.request().temperature());
		assertEquals(Integer.valueOf(64), parsed.request().maxTokens());
		assertEquals(Double.valueOf(0.9), parsed.request().topP());
		List<ChatMessage> messages = parsed.request().messages();
		assertEquals(3, messages.size());
		assertEquals(Role.SYSTEM, messages.get(0).role());
		assertEquals("sys指令", messages.get(0).content());
		assertEquals(Role.USER, messages.get(1).role());
		assertEquals("西安天气如何？", messages.get(1).content());
		assertEquals(Role.TOOL, messages.get(2).role());
		assertEquals("call-1", messages.get(2).toolCallId());
		// 响应侧
		assertEquals("resp-1", parsed.response().id());
		assertEquals("test-model", parsed.response().model());
		TokenUsage usage = parsed.response().usage();
		assertNotNull(usage);
		assertEquals(2, usage.promptTokens());
		assertEquals(3, usage.completionTokens());
		assertEquals(5, usage.totalTokens());
		Choice choice = parsed.response().choices().get(0);
		assertEquals(0, choice.index());
		assertEquals("tool_calls", choice.finishReason());
		List<ToolCall> calls = choice.message().toolCalls();
		assertNotNull(calls);
		assertEquals(1, calls.size());
		assertEquals("call-1", calls.get(0).id());
		assertEquals("get_weather", calls.get(0).name());
		assertEquals("{\"city\":\"西安\"}", calls.get(0).argumentsJson());
	}

	@Test
	public void 文本助手消息往返() {
		ChatRequest request = ChatRequest.builder()
			.model("m")
			.messages(ChatMessage.user("你好"))
			.build();
		ChatResponse response = ChatResponse.of("r", "m",
			List.of(Choice.of(0, ChatMessage.assistant("你好呀"), "stop")),
			TokenUsage.of(1, 1, 2), null);
		EvalTrace parsed = EvalTrace.parse(EvalTrace.toJson(
			new EvalTrace("c", 1L, request, response)));
		assertEquals("你好呀", parsed.response().firstText());
		assertEquals(Role.ASSISTANT, parsed.response().choices().get(0).message().role());
	}

	@Test
	public void 缺字段与坏JSON给清晰错误() {
		assertThrows(EvalException.class, () -> EvalTrace.parse(null));
		assertThrows(EvalException.class, () -> EvalTrace.parse("   "));
		assertThrows(EvalException.class, () -> EvalTrace.parse("{不是json"));
		assertThrows(EvalException.class, () -> EvalTrace.parse("[1,2,3]"));
		assertThrows(EvalException.class, () -> EvalTrace.parse("{}"));
		assertThrows(EvalException.class, () -> EvalTrace.parse("{\"request\":{\"model\":\"m\",\"messages\":[]}}"));
		assertThrows(EvalException.class,
			() -> EvalTrace.parse("{\"request\":{\"model\":\"m\",\"messages\":[{\"role\":\"superman\",\"content\":\"x\"}],\"model\":\"m\"},\"response\":{}}"));
		assertThrows(EvalException.class, () -> EvalTrace.toJson(null));
	}

	@Test
	public void 构造校验请求响应非空() {
		ChatRequest request = ChatRequest.builder()
			.model("m").messages(ChatMessage.user("q")).build();
		assertThrows(EvalException.class, () -> new EvalTrace("c", 1L, request, null));
		assertThrows(EvalException.class,
			() -> new EvalTrace("c", 1L, null, sampleResponse()));
	}

	@Test
	public void 空usage与空choices容忍() {
		ChatRequest request = ChatRequest.builder()
			.model("m").messages(ChatMessage.user("q")).build();
		ChatResponse response = ChatResponse.of("r", "m", List.of(), null, null);
		EvalTrace parsed = EvalTrace.parse(EvalTrace.toJson(
			new EvalTrace(null, 0L, request, response)));
		assertNull(parsed.id());
		assertTrue(parsed.response().choices().isEmpty());
		assertNull(parsed.response().usage());
	}

	@Test
	public void user标识与思考内容往返() {
		ChatMessage withReasoning = ChatMessage.of(Role.ASSISTANT, "答案", null, null, null, null, "推理过程…");
		ChatRequest request = ChatRequest.builder()
			.model("m")
			.user("u-1")
			.messages(ChatMessage.user("q"), withReasoning)
			.build();
		ChatResponse response = ChatResponse.of("r", "m",
			List.of(Choice.of(0, ChatMessage.assistant("a"), "stop")),
			TokenUsage.of(1, 1, 2), null);
		EvalTrace parsed = EvalTrace.parse(EvalTrace.toJson(
			new EvalTrace("c", 1L, request, response)));
		assertEquals("u-1", parsed.request().user());
		assertEquals("推理过程…", parsed.request().messages().get(1).reasoningContent());
	}

	@Test
	public void 空模型与缺角色给清晰错误() {
		assertThrows(EvalException.class,
			() -> EvalTrace.parse("{\"request\":{\"model\":\"  \",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]},\"response\":{}}"));
		assertThrows(EvalException.class,
			() -> EvalTrace.parse("{\"request\":{\"model\":\"m\",\"messages\":[{\"content\":\"q\"}]},\"response\":{}}"));
		assertThrows(EvalException.class, () -> EvalTrace.parse("{\"request\":null,\"response\":{}}"));
	}
}
