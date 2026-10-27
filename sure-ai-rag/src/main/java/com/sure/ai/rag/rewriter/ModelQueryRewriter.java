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

package com.sure.ai.rag.rewriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.prompt.PromptTemplate;

/**
 * 基于对话模型的查询改写器：让 LLM 把原始查询扩写为多个语义等价的表述。
 *
 * <p>内部使用 {@link PromptTemplate} 渲染提示词，要求模型每行输出一个改写查询、
 * 不编号、不解释；实现侧按行拆分、去空行、截断到 count 条。</p>
 *
 * <p>容错策略：若模型调用抛异常、无返回文本或解析为空，则回退为单元素列表
 * （仅返回原始查询），绝不向上抛出，保证检索链路可继续运行。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class ModelQueryRewriter implements QueryRewriter {

	/** 默认改写提示词模板，含 {count} 与 {query} 两个占位符。 */
	public static final String DEFAULT_PROMPT_TEMPLATE =
			"请将以下用户问题改写为 {count} 个语义等价但表述不同的查询，"
			+ "每行一个，不要编号，不要解释：\n{query}";

	private final AiClient chatClient;
	private final String model;
	private final PromptTemplate promptTemplate;
	private final Integer maxTokens;

	private ModelQueryRewriter(Builder builder) {
		this.chatClient = builder.chatClient;
		this.model = builder.model;
		this.promptTemplate = builder.promptTemplate == null
				? PromptTemplate.fromString(DEFAULT_PROMPT_TEMPLATE) : builder.promptTemplate;
		this.maxTokens = builder.maxTokens;
	}

	/**
	 * 便捷构造器。
	 *
	 * @param chatClient 对话客户端
	 * @param model      模型名
	 */
	public ModelQueryRewriter(AiClient chatClient, String model) {
		this(builder().chatClient(chatClient).model(model));
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public List<String> rewrite(String query, int count) {
		if (count <= 0) {
			throw new IllegalArgumentException("count must be > 0");
		}
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("count", count);
		vars.put("query", query == null ? "" : query);
		String prompt = this.promptTemplate.render(vars);

		int maxTokensToUse = this.maxTokens == null ? 512 : this.maxTokens;
		String text;
		try {
			ChatRequest request = ChatRequest.builder()
					.model(this.model)
					.messages(List.of(ChatMessage.user(prompt)))
					.maxTokens(maxTokensToUse)
					.build();
			ChatResponse response = this.chatClient.chat(request);
			text = response == null ? null : response.firstText();
		} catch (RuntimeException e) {
			return List.of(query);
		}

		if (text == null || text.isBlank()) {
			return List.of(query);
		}
		List<String> result = new ArrayList<>();
		for (String line : text.split("\\R")) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
			if (result.size() >= count) {
				break;
			}
		}
		if (result.isEmpty()) {
			return List.of(query);
		}
		return List.copyOf(result);
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private AiClient chatClient;
		private String model;
		private PromptTemplate promptTemplate;
		private Integer maxTokens;

		private Builder() {
		}

		/**
		 * 设置对话客户端。
		 *
		 * @param chatClient 对话客户端
		 * @return this
		 */
		public Builder chatClient(AiClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		/**
		 * 设置模型名。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 自定义改写提示词模板（必须含 {count} 与 {query} 占位符）。
		 *
		 * @param promptTemplate 提示词模板
		 * @return this
		 */
		public Builder promptTemplate(PromptTemplate promptTemplate) {
			this.promptTemplate = promptTemplate;
			return this;
		}

		/**
		 * 设置 maxTokens。
		 *
		 * @param maxTokens 最大 token 数
		 * @return this
		 */
		public Builder maxTokens(int maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		/**
		 * 构建改写器。
		 *
		 * @return 改写器
		 */
		public ModelQueryRewriter build() {
			if (this.chatClient == null) {
				throw new IllegalArgumentException("chatClient must not be null");
			}
			if (this.model == null || this.model.isBlank()) {
				throw new IllegalArgumentException("model must not be blank");
			}
			return new ModelQueryRewriter(this);
		}
	}
}
