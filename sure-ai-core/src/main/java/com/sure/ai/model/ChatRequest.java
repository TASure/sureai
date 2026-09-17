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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.tool.lang.Assert;

/**
 * 对话请求。
 *
 * <p>不可变，通过 {@link #builder()} 构造；model 与 messages 为必填。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class ChatRequest {

	private final String model;
	private final List<ChatMessage> messages;
	private final Double temperature;
	private final Integer maxTokens;
	private final Double topP;
	private final Object stop;
	private final boolean stream;
	private final String user;
	private final List<ToolSpec> tools;
	private final Object toolChoice;
	private final Double presencePenalty;
	private final Double frequencyPenalty;
	private final Integer seed;
	private final Map<String, Object> extra;

	private ChatRequest(Builder b) {
		this.model = b.model;
		this.messages = List.copyOf(b.messages);
		this.temperature = b.temperature;
		this.maxTokens = b.maxTokens;
		this.topP = b.topP;
		this.stop = b.stop;
		this.stream = b.stream;
		this.user = b.user;
		this.tools = b.tools == null ? null : List.copyOf(b.tools);
		this.toolChoice = b.toolChoice;
		this.presencePenalty = b.presencePenalty;
		this.frequencyPenalty = b.frequencyPenalty;
		this.seed = b.seed;
		this.extra = b.extra == null ? Map.of() : Map.copyOf(b.extra);
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
	 * Builder。
	 */
	public static final class Builder {

		private String model;
		private List<ChatMessage> messages;
		private Double temperature;
		private Integer maxTokens;
		private Double topP;
		private Object stop;
		private boolean stream;
		private String user;
		private List<ToolSpec> tools;
		private Object toolChoice;
		private Double presencePenalty;
		private Double frequencyPenalty;
		private Integer seed;
		private Map<String, Object> extra;

		private Builder() {
		}

		/**
		 * 设置模型。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 设置消息列表。
		 *
		 * @param messages 消息
		 * @return this
		 */
		public Builder messages(List<ChatMessage> messages) {
			this.messages = messages;
			return this;
		}

		/**
		 * 设置消息列表。
		 *
		 * @param msgs 消息
		 * @return this
		 */
		public Builder messages(ChatMessage... msgs) {
			this.messages = List.of(msgs);
			return this;
		}

		/**
		 * 设置 temperature。
		 *
		 * @param temperature temperature
		 * @return this
		 */
		public Builder temperature(Double temperature) {
			this.temperature = temperature;
			return this;
		}

		/**
		 * 设置 maxTokens。
		 *
		 * @param maxTokens 最大 token 数
		 * @return this
		 */
		public Builder maxTokens(Integer maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		/**
		 * 设置 topP。
		 *
		 * @param topP top_p
		 * @return this
		 */
		public Builder topP(Double topP) {
			this.topP = topP;
			return this;
		}

		/**
		 * 设置 stop 字符串。
		 *
		 * @param stop stop 字符串
		 * @return this
		 */
		public Builder stop(String stop) {
			this.stop = stop;
			return this;
		}

		/**
		 * 设置 stop 列表。
		 *
		 * @param stop stop 列表
		 * @return this
		 */
		public Builder stop(List<String> stop) {
			this.stop = stop;
			return this;
		}

		/**
		 * 设置 stream。
		 *
		 * @param stream 是否流式
		 * @return this
		 */
		public Builder stream(boolean stream) {
			this.stream = stream;
			return this;
		}

		/**
		 * 设置 user。
		 *
		 * @param user user 标识
		 * @return this
		 */
		public Builder user(String user) {
			this.user = user;
			return this;
		}

		/**
		 * 设置工具列表。
		 *
		 * @param tools 工具
		 * @return this
		 */
		public Builder tools(List<ToolSpec> tools) {
			this.tools = tools;
			return this;
		}

		/**
		 * 设置 toolChoice（"none"/"auto" 或对象）。
		 *
		 * @param toolChoice tool_choice
		 * @return this
		 */
		public Builder toolChoice(Object toolChoice) {
			this.toolChoice = toolChoice;
			return this;
		}

		/**
		 * 设置 presencePenalty。
		 *
		 * @param presencePenalty presence_penalty
		 * @return this
		 */
		public Builder presencePenalty(Double presencePenalty) {
			this.presencePenalty = presencePenalty;
			return this;
		}

		/**
		 * 设置 frequencyPenalty。
		 *
		 * @param frequencyPenalty frequency_penalty
		 * @return this
		 */
		public Builder frequencyPenalty(Double frequencyPenalty) {
			this.frequencyPenalty = frequencyPenalty;
			return this;
		}

		/**
		 * 设置 seed。
		 *
		 * @param seed 随机种子
		 * @return this
		 */
		public Builder seed(Integer seed) {
			this.seed = seed;
			return this;
		}

		/**
		 * 追加透传字段。
		 *
		 * @param key   键
		 * @param value 值
		 * @return this
		 */
		public Builder extra(String key, Object value) {
			if (this.extra == null) {
				this.extra = new LinkedHashMap<>();
			}
			this.extra.put(key, value);
			return this;
		}

		/**
		 * 构建请求，校验必填字段。
		 *
		 * @return 请求
		 */
		public ChatRequest build() {
			Assert.notBlank(this.model, "model must not be blank");
			Assert.notEmpty(this.messages, "messages must not be empty");
			return new ChatRequest(this);
		}
	}

	/** 模型名。 */
	public String model() {
		return this.model;
	}

	/** 消息列表。 */
	public List<ChatMessage> messages() {
		return this.messages;
	}

	/** temperature。 */
	public Double temperature() {
		return this.temperature;
	}

	/** maxTokens。 */
	public Integer maxTokens() {
		return this.maxTokens;
	}

	/** topP。 */
	public Double topP() {
		return this.topP;
	}

	/** stop（String 或 List）。 */
	public Object stop() {
		return this.stop;
	}

	/** 是否流式。 */
	public boolean stream() {
		return this.stream;
	}

	/** user。 */
	public String user() {
		return this.user;
	}

	/** 工具列表。 */
	public List<ToolSpec> tools() {
		return this.tools;
	}

	/** toolChoice。 */
	public Object toolChoice() {
		return this.toolChoice;
	}

	/** presencePenalty。 */
	public Double presencePenalty() {
		return this.presencePenalty;
	}

	/** frequencyPenalty。 */
	public Double frequencyPenalty() {
		return this.frequencyPenalty;
	}

	/** seed。 */
	public Integer seed() {
		return this.seed;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
