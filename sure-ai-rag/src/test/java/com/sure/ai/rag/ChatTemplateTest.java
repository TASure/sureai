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

package com.sure.ai.rag;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.Role;
import com.sure.ai.rag.prompt.ChatTemplate;

/**
 * {@link ChatTemplate} 单元测试。
 *
 * @author sureai
 * @since 1.1.0
 */
public class ChatTemplateTest {

	@Test
	public void testChatTemplateRender() {
		ChatTemplate t = ChatTemplate.builder()
			.system("你是{role}助手")
			.user("请问{question}")
			.build();
		List<ChatMessage> out = t.render(Map.of("role", "知识库", "question", "天气"));
		assertEquals(2, out.size());
		assertEquals(Role.SYSTEM, out.get(0).role());
		assertEquals("你是知识库助手", out.get(0).content());
		assertEquals(Role.USER, out.get(1).role());
		assertEquals("请问天气", out.get(1).content());
	}

	@Test
	public void testChatTemplateFewShot() {
		ChatMessage exUser = ChatMessage.user("例子问题1");
		ChatMessage exAsst = ChatMessage.assistant("例子回答1");
		ChatTemplate t = ChatTemplate.builder()
			.system("系统指令")
			.user("{question}")
			.addExample(exUser, exAsst)
			.build();
		List<ChatMessage> out = t.render(Map.of("question", "真实问题"));
		// 顺序：system -> exampleUser -> exampleAsst -> user
		assertEquals(4, out.size());
		assertEquals(Role.SYSTEM, out.get(0).role());
		assertEquals(Role.USER, out.get(1).role());
		assertEquals("例子问题1", out.get(1).content());
		assertEquals(Role.ASSISTANT, out.get(2).role());
		assertEquals("例子回答1", out.get(2).content());
		assertEquals(Role.USER, out.get(3).role());
		assertEquals("真实问题", out.get(3).content());
	}

	@Test
	public void testFewShotWithoutSystem() {
		ChatTemplate t = ChatTemplate.builder()
			.user("{q}")
			.addExample(ChatMessage.user("eu"), ChatMessage.assistant("ea"))
			.build();
		List<ChatMessage> out = t.render(Map.of("q", "real"));
		assertEquals(3, out.size());
		assertEquals("eu", out.get(0).content());
		assertEquals("ea", out.get(1).content());
		assertEquals("real", out.get(2).content());
	}

	@Test
	public void testFromMessages() {
		List<ChatMessage> templates = List.of(
				ChatMessage.system("sys {x}"),
				ChatMessage.user("user {y}"));
		ChatTemplate t = ChatTemplate.fromMessages(templates);
		List<ChatMessage> out = t.render(Map.of("x", "X", "y", "Y"));
		assertEquals(2, out.size());
		assertEquals("sys X", out.get(0).content());
		assertEquals("user Y", out.get(1).content());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testEmptyRejected() {
		ChatTemplate.builder().build();
	}
}
