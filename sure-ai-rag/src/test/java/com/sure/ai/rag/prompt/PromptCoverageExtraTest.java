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
package com.sure.ai.rag.prompt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.model.ChatMessage;

/**
 * 提示词模板补覆盖测试：{@link ChatTemplate} 仅系统消息时 few-shot 示例追加在末尾、
 * assistant 消息模板 setter 链式构建；{@link PromptTemplate} 可变参数渲染。
 *
 * @author sureai
 * @since 2.6.0
 */
public class PromptCoverageExtraTest {

	/** 仅有系统消息：示例追加在系统消息之后。 */
	@Test
	public void testChatTemplateExamplesAfterSystemOnly() {
		ChatTemplate template = ChatTemplate.builder().system("你是助手")
				.addExample(ChatMessage.user("问题A"), ChatMessage.assistant("回答A"))
				.build();
		List<ChatMessage> messages = template.render(java.util.Map.of());
		// 系统消息 + 两个示例消息
		assertEquals(3, messages.size());
		assertEquals("你是助手", messages.get(0).content());
		assertTrue(messages.get(1).content().contains("问题A"));
	}

	/** assistant 模板消息 setter。 */
	@Test
	public void testChatTemplateAssistantMessage() {
		ChatTemplate template = ChatTemplate.builder().user("用户说:{q}")
				.assistant("助手答:{a}").build();
		List<ChatMessage> messages = template.render(java.util.Map.of("q", "1", "a", "2"));
		assertEquals(2, messages.size());
	}

	/** PromptTemplate 可变参数渲染。 */
	@Test
	public void testPromptTemplateVarargsRender() {
		PromptTemplate t = PromptTemplate.fromString("你好 {name}，今年 {age} 岁");
		assertEquals("你好 张三，今年 18 岁", t.render("name", "张三", "age", "18"));
	}
}
