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

package com.sure.ai.evals.dataset;

import java.util.List;
import java.util.Map;

import com.sure.ai.model.ChatMessage;
import com.sure.tool.lang.Assert;

/**
 * 单条评估用例：模型输入（多轮消息）+ 期望输出 + 标签 / 元数据 / 期望工具调用序列。
 *
 * <p>不可变，通过 {@link #builder()} 构造。{@code input} 为送入被测模型的完整消息列表，
 * {@code expected} 为参考答案（确定性指标与语义指标据此打分，LLM 裁判亦会参考）；
 * {@code expectedTools} 为期望出现的工具名有序序列，用于工具调用正确性核对。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalCase {

	private final String id;
	private final List<ChatMessage> input;
	private final String expected;
	private final List<String> tags;
	private final Map<String, Object> metadata;
	private final List<String> expectedTools;

	private EvalCase(Builder b) {
		this.id = b.id;
		this.input = List.copyOf(b.input);
		this.expected = b.expected;
		this.tags = List.copyOf(b.tags);
		this.metadata = Map.copyOf(b.metadata);
		this.expectedTools = List.copyOf(b.expectedTools);
	}

	/**
	 * 便捷构造：单条 user 文本输入。
	 *
	 * @param id       用例 ID
	 * @param input    用户输入文本
	 * @param expected 期望输出
	 * @return 用例
	 */
	public static EvalCase of(String id, String input, String expected) {
		return builder()
			.id(id)
			.input(List.of(ChatMessage.user(input)))
			.expected(expected)
			.build();
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
	 * 用例 ID。
	 *
	 * @return ID
	 */
	public String id() {
		return this.id;
	}

	/**
	 * 送入被测模型的消息列表。
	 *
	 * @return 不可变消息列表
	 */
	public List<ChatMessage> input() {
		return this.input;
	}

	/**
	 * 期望输出（参考答案）。
	 *
	 * @return 期望输出，可能为 null
	 */
	public String expected() {
		return this.expected;
	}

	/**
	 * 标签列表。
	 *
	 * @return 不可变标签列表
	 */
	public List<String> tags() {
		return this.tags;
	}

	/**
	 * 元数据。
	 *
	 * @return 不可变元数据
	 */
	public Map<String, Object> metadata() {
		return this.metadata;
	}

	/**
	 * 期望工具名有序序列。
	 *
	 * @return 不可变工具名列表
	 */
	public List<String> expectedTools() {
		return this.expectedTools;
	}

	/**
	 * 拼接全部 user 消息文本（数据集加载与报告展示的便捷口径）。
	 *
	 * @return user 文本，多条以换行连接，无 user 消息时返回空串
	 */
	public String inputText() {
		StringBuilder sb = new StringBuilder();
		for (ChatMessage message : this.input) {
			if (message.content() != null && !message.content().isEmpty()) {
				if (!sb.isEmpty()) {
					sb.append('\n');
				}
				sb.append(message.content());
			}
		}
		return sb.toString();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String id;
		private List<ChatMessage> input = List.of();
		private String expected;
		private List<String> tags = List.of();
		private Map<String, Object> metadata = Map.of();
		private List<String> expectedTools = List.of();

		private Builder() {
		}

		/**
		 * 设置用例 ID。
		 *
		 * @param id ID
		 * @return this
		 */
		public Builder id(String id) {
			this.id = id;
			return this;
		}

		/**
		 * 设置输入消息列表。
		 *
		 * @param input 消息列表
		 * @return this
		 */
		public Builder input(List<ChatMessage> input) {
			Assert.notNull(input, "input 不能为 null");
			this.input = List.copyOf(input);
			return this;
		}

		/**
		 * 设置单条 user 文本输入。
		 *
		 * @param userInput 用户文本
		 * @return this
		 */
		public Builder userInput(String userInput) {
			this.input = List.of(ChatMessage.user(userInput));
			return this;
		}

		/**
		 * 设置期望输出。
		 *
		 * @param expected 期望输出
		 * @return this
		 */
		public Builder expected(String expected) {
			this.expected = expected;
			return this;
		}

		/**
		 * 设置标签。
		 *
		 * @param tags 标签
		 * @return this
		 */
		public Builder tags(List<String> tags) {
			Assert.notNull(tags, "tags 不能为 null");
			this.tags = List.copyOf(tags);
			return this;
		}

		/**
		 * 设置元数据。
		 *
		 * @param metadata 元数据
		 * @return this
		 */
		public Builder metadata(Map<String, Object> metadata) {
			Assert.notNull(metadata, "metadata 不能为 null");
			this.metadata = Map.copyOf(metadata);
			return this;
		}

		/**
		 * 设置期望工具名序列。
		 *
		 * @param expectedTools 工具名
		 * @return this
		 */
		public Builder expectedTools(List<String> expectedTools) {
			Assert.notNull(expectedTools, "expectedTools 不能为 null");
			this.expectedTools = List.copyOf(expectedTools);
			return this;
		}

		/**
		 * 构建用例。
		 *
		 * @return 用例
		 */
		public EvalCase build() {
			if (this.id == null || this.id.isBlank()) {
				this.id = "case-" + Integer.toHexString(System.identityHashCode(this));
			}
			return new EvalCase(this);
		}
	}
}
