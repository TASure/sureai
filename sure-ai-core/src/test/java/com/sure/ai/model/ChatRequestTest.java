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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * {@link ChatRequest} Builder 单元测试。
 *
 * @author sureai
 * @since 0.1.0
 */
public class ChatRequestTest {

	/** 全字段 Builder。 */
	@Test
	public void testBuilder() {
		ChatRequest req = ChatRequest.builder()
			.model("gpt-4")
			.messages(List.of(ChatMessage.user("hi")))
			.temperature(0.5)
			.maxTokens(100)
			.topP(0.9)
			.stop("END")
			.user("u1")
			.presencePenalty(0.1)
			.frequencyPenalty(0.2)
			.seed(7)
			.extra("custom", 1)
			.build();
		assertEquals("gpt-4", req.model());
		assertEquals(1, req.messages().size());
		assertEquals(Double.valueOf(0.5), req.temperature());
		assertEquals(Integer.valueOf(100), req.maxTokens());
		assertEquals(Double.valueOf(0.9), req.topP());
		assertEquals("END", req.stop());
		assertEquals("u1", req.user());
		assertEquals(Double.valueOf(0.1), req.presencePenalty());
		assertEquals(Double.valueOf(0.2), req.frequencyPenalty());
		assertEquals(Integer.valueOf(7), req.seed());
		assertEquals(1, req.extra().get("custom"));
		assertFalse(req.stream());
	}

	/** stop 可为列表。 */
	@Test
	public void testStopList() {
		ChatRequest req = ChatRequest.builder().model("m").messages(ChatMessage.user("x"))
			.stop(List.of("a", "b")).build();
		assertEquals(List.of("a", "b"), req.stop());
	}

	/** 必填 model 校验。 */
	@Test
	public void testRequireModel() {
		assertThrows(RuntimeException.class, () -> ChatRequest.builder()
			.messages(ChatMessage.user("x")).build());
	}

	/** 必填 messages 校验。 */
	@Test
	public void testRequireMessages() {
		assertThrows(RuntimeException.class, () -> ChatRequest.builder().model("m").build());
	}

	/** toolChoice 可设对象。 */
	@Test
	public void testToolChoiceObject() {
		ChatRequest req = ChatRequest.builder().model("m").messages(ChatMessage.user("x"))
			.toolChoice("auto").build();
		assertEquals("auto", req.toolChoice());
	}

	/** 默认 extra 为空不可变。 */
	@Test
	public void testExtraImmutable() {
		ChatRequest req = ChatRequest.builder().model("m").messages(ChatMessage.user("x")).build();
		assertTrue(req.extra().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> req.extra().put("a", 1));
	}

	/** varargs messages。 */
	@Test
	public void testVarargsMessages() {
		ChatRequest req = ChatRequest.builder().model("m")
			.messages(ChatMessage.system("s"), ChatMessage.user("u")).build();
		assertEquals(2, req.messages().size());
		assertEquals(Role.SYSTEM, req.messages().get(0).role());
	}

	/** 默认字段为 null。 */
	@Test
	public void testDefaultsNull() {
		ChatRequest req = ChatRequest.builder().model("m").messages(ChatMessage.user("x")).build();
		assertNull(req.temperature());
		assertNull(req.tools());
		assertNull(req.toolChoice());
	}
}
