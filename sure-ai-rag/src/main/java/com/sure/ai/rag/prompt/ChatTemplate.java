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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.Role;

/**
 * 多消息对话模板：把一组带占位符的 {@link ChatMessage} 模板一次性渲染为消息列表。
 *
 * <p>每条模板消息的 content 按 {@link PromptTemplate} 规则渲染。
 * 支持 few-shot 示例：通过 {@link Builder#addExample(ChatMessage, ChatMessage)}
 * 注册 user/assistant 示例对，{@link #render(Map)} 时会把所有示例对
 * 插入到系统消息之后、第一条用户消息之前。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class ChatTemplate {

	private final List<TemplatedMessage> messages;
	private final List<ChatMessage> examples;

	private ChatTemplate(Builder builder) {
		this.messages = List.copyOf(builder.messages);
		this.examples = List.copyOf(builder.examples);
	}

	/**
	 * 从已有消息列表构造（每条消息的 content 视为模板）。
	 *
	 * @param templates 模板消息
	 * @return 对话模板
	 */
	public static ChatTemplate fromMessages(List<ChatMessage> templates) {
		Builder builder = builder();
		for (ChatMessage message : templates) {
			String content = message.content() == null ? "" : message.content();
			builder.messages.add(new TemplatedMessage(message.role(), content));
		}
		return builder.build();
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 渲染为消息列表。
	 *
	 * @param variables 变量表
	 * @return 渲染后的不可变消息列表
	 */
	public List<ChatMessage> render(Map<String, Object> variables) {
		List<ChatMessage> result = new ArrayList<>();
		boolean inserted = false;
		for (TemplatedMessage tm : this.messages) {
			if (!inserted && tm.role != Role.SYSTEM) {
				result.addAll(this.examples);
				inserted = true;
			}
			String content = PromptTemplate.fromString(tm.content).render(variables);
			result.add(ChatMessage.of(tm.role, content, null, null, null, null));
		}
		if (!inserted) {
			result.addAll(this.examples);
		}
		return List.copyOf(result);
	}

	/** 单条模板消息（角色 + 模板文本）。 */
	private static final class TemplatedMessage {

		private final Role role;
		private final String content;

		TemplatedMessage(Role role, String content) {
			this.role = role;
			this.content = content;
		}
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private final List<TemplatedMessage> messages = new ArrayList<>();
		private final List<ChatMessage> examples = new ArrayList<>();

		private Builder() {
		}

		/**
		 * 追加系统消息模板。
		 *
		 * @param template 模板
		 * @return this
		 */
		public Builder system(String template) {
			this.messages.add(new TemplatedMessage(Role.SYSTEM, template));
			return this;
		}

		/**
		 * 追加用户消息模板。
		 *
		 * @param template 模板
		 * @return this
		 */
		public Builder user(String template) {
			this.messages.add(new TemplatedMessage(Role.USER, template));
			return this;
		}

		/**
		 * 追加助手消息模板。
		 *
		 * @param template 模板
		 * @return this
		 */
		public Builder assistant(String template) {
			this.messages.add(new TemplatedMessage(Role.ASSISTANT, template));
			return this;
		}

		/**
		 * 注册一组 few-shot 示例对（用户问 + 助手答），渲染时插入系统消息之后、
		 * 第一条用户消息之前。示例本身已是完整消息，不再做占位符替换。
		 *
		 * @param userExample      示例用户消息
		 * @param assistantExample 示例助手消息
		 * @return this
		 */
		public Builder addExample(ChatMessage userExample, ChatMessage assistantExample) {
			this.examples.add(userExample);
			this.examples.add(assistantExample);
			return this;
		}

		/**
		 * 构建对话模板。
		 *
		 * @return 对话模板
		 */
		public ChatTemplate build() {
			if (this.messages.isEmpty()) {
				throw new IllegalArgumentException("ChatTemplate must have at least one message");
			}
			return new ChatTemplate(this);
		}
	}
}
