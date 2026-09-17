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

package com.sure.ai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * 模型类静态工厂与不可变性测试。
 *
 * @author sureai
 * @since 0.1.0
 */
public class ModelClassesTest {

	/** Role value/fromValue。 */
	@Test
	public void testRole() {
		assertEquals("system", Role.SYSTEM.value());
		assertEquals(Role.USER, Role.fromValue("user"));
		assertEquals(Role.ASSISTANT, Role.fromValue("assistant"));
		assertEquals(Role.TOOL, Role.fromValue("tool"));
		assertThrows(AiException.class, () -> Role.fromValue("bogus"));
	}

	/** 消息静态工厂。 */
	@Test
	public void testMessageFactories() {
		assertEquals(Role.SYSTEM, ChatMessage.system("s").role());
		assertEquals("hi", ChatMessage.user("hi").content());
		assertEquals(Role.ASSISTANT, ChatMessage.assistant("a").role());
		ChatMessage tool = ChatMessage.tool("call-1", "result");
		assertEquals(Role.TOOL, tool.role());
		assertEquals("call-1", tool.toolCallId());
		assertEquals("result", tool.content());
	}

	/** 多模态 user 消息。 */
	@Test
	public void testUserParts() {
		List<MessagePart> parts = List.of(TextPart.of("t"), ImagePart.ofUrl("http://x/y.png"));
		ChatMessage m = ChatMessage.user(parts);
		assertEquals(2, m.parts().size());
		assertEquals("text", m.parts().get(0).type());
		assertEquals("image_url", m.parts().get(1).type());
	}

	/** ImagePart base64 解析。 */
	@Test
	public void testImageBase64() {
		ImagePart p = ImagePart.ofBase64("QQ==", "image/png");
		assertEquals("data:image/png;base64,QQ==", p.resolvedUrl());
	}

	/** 不可变性：构造后修改入参集合不影响消息。 */
	@Test
	public void testImmutability() {
		List<ToolCall> calls = new ArrayList<>();
		calls.add(ToolCall.of("id1", "fn", "{}"));
		ChatMessage m = ChatMessage.assistant(calls);
		calls.add(ToolCall.of("id2", "fn2", "{}"));
		assertEquals(1, m.toolCalls().size());
		assertThrows(UnsupportedOperationException.class, () -> m.toolCalls().add(ToolCall.of("x", "y", "{}")));
	}

	/** TokenUsage。 */
	@Test
	public void testUsage() {
		TokenUsage u = TokenUsage.of(10, 5, 15);
		assertEquals(10, u.promptTokens());
		assertEquals(5, u.completionTokens());
		assertEquals(15, u.totalTokens());
	}

	/** Choice/ChatResponse。 */
	@Test
	public void testChoiceAndResponse() {
		ChatMessage msg = ChatMessage.assistant("hello");
		Choice c = Choice.of(0, msg, "stop");
		assertEquals(0, c.index());
		assertEquals("stop", c.finishReason());
		ChatResponse r = ChatResponse.of("id1", "gpt", List.of(c), TokenUsage.of(1, 2, 3), "{}");
		assertEquals("hello", r.firstText());
		assertEquals("id1", r.id());
		assertTrue(r.choices().size() == 1);
	}

	/** ToolCall/ToolFunction/ToolSpec。 */
	@Test
	public void testToolRecords() {
		ToolCall tc = ToolCall.of("id", "name", "{}");
		assertEquals("id", tc.id());
		ToolFunction fn = ToolFunction.of("n", "d", "{}");
		assertEquals("n", fn.name());
		ToolSpec spec = ToolSpec.of(fn);
		assertEquals(fn, spec.function());
	}

	/** 嵌入模型。 */
	@Test
	public void testEmbeddingModels() {
		EmbeddingRequest req = EmbeddingRequest.of("m", List.of("a", "b"));
		assertEquals(2, req.input().size());
		float[] v = new float[] { 1.0f, 2.0f };
		EmbeddingResponse resp = EmbeddingResponse.of("m", List.of(v), TokenUsage.of(1, 0, 1));
		assertEquals(1, resp.embeddings().size());
		assertEquals(1, resp.usage().promptTokens());
		assertEquals(2, resp.embeddings().get(0).length);
	}

	/** ChatStreamChunk。 */
	@Test
	public void testChunk() {
		ChatStreamChunk ch = ChatStreamChunk.of("id", Role.ASSISTANT, "hi", null, null);
		assertEquals("hi", ch.deltaText());
		assertEquals(Role.ASSISTANT, ch.role());
	}
}
