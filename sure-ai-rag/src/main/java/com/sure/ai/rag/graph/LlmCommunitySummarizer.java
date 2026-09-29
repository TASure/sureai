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

package com.sure.ai.rag.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.prompt.PromptTemplate;
import com.sure.tool.lang.Assert;

/**
 * 基于对话模型的社区摘要器。
 *
 * <p>聚合社区内的实体名与关系三元组，渲染提示词后让 LLM 生成一段主题摘要。
 * 容错策略：LLM 调用抛异常或返回空文本时，回退为实体/关系列表的机械拼接，
 * 绝不向上抛出。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class LlmCommunitySummarizer implements CommunitySummarizer {

	/** 默认摘要提示词模板，含 {entities} 与 {relations} 占位符。 */
	public static final String DEFAULT_PROMPT_TEMPLATE =
			"下面是知识图谱中一个社区（相互关联的实体子图）的实体与关系，"
			+ "请用一到两句话概括该社区共同讨论的主题，直接输出摘要，不要解释：\n"
			+ "实体：{entities}\n关系：{relations}";

	/** 默认 maxTokens。 */
	public static final int DEFAULT_MAX_TOKENS = 256;

	private final AiClient chatClient;
	private final String model;
	private final PromptTemplate promptTemplate;
	private final int maxTokens;

	private LlmCommunitySummarizer(Builder builder) {
		this.chatClient = builder.chatClient;
		this.model = builder.model;
		this.promptTemplate = builder.promptTemplate == null
				? PromptTemplate.fromString(DEFAULT_PROMPT_TEMPLATE) : builder.promptTemplate;
		this.maxTokens = builder.maxTokens == null ? DEFAULT_MAX_TOKENS : builder.maxTokens;
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
	public String summarize(GraphCommunity community, KnowledgeGraph graph,
			List<Document> sourceDocs) {
		Assert.notNull(community, "community 不能为 null");
		Assert.notNull(graph, "graph 不能为 null");

		String entityText = collectEntities(community, graph);
		String relationText = collectRelations(community, graph);
		String fallback = "实体：" + entityText + "；关系：" + relationText;

		try {
			String prompt = this.promptTemplate.render(Map.of(
					"entities", entityText,
					"relations", relationText));
			ChatRequest request = ChatRequest.builder()
					.model(this.model)
					.messages(List.of(ChatMessage.user(prompt)))
					.maxTokens(this.maxTokens)
					.build();
			ChatResponse response = this.chatClient.chat(request);
			String text = response == null ? null : response.firstText();
			if (text == null || text.isBlank()) {
				return fallback;
			}
			return text.trim();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/**
	 * 聚合社区内实体展示名（去重，保持稳定顺序）。
	 */
	private static String collectEntities(GraphCommunity community, KnowledgeGraph graph) {
		Set<String> names = new LinkedHashSet<>();
		for (String id : community.entityIds()) {
			GraphEntity entity = graph.getEntity(id);
			if (entity != null) {
				names.add(entity.name());
			}
		}
		return String.join("、", names);
	}

	/**
	 * 聚合社区内两端都在社区中的关系三元组文本。
	 */
	private static String collectRelations(GraphCommunity community, KnowledgeGraph graph) {
		Set<String> members = new LinkedHashSet<>(community.entityIds());
		List<String> triples = new ArrayList<>();
		for (GraphRelation relation : graph.relations()) {
			if (members.contains(relation.sourceId()) && members.contains(relation.targetId())) {
				GraphEntity s = graph.getEntity(relation.sourceId());
				GraphEntity t = graph.getEntity(relation.targetId());
				triples.add(nameOf(s) + "-" + relation.label() + "->" + nameOf(t));
			}
		}
		return String.join("；", triples);
	}

	private static String nameOf(GraphEntity entity) {
		return entity == null ? "?" : entity.name();
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
		 * 自定义摘要提示词模板（必须含 {entities} 与 {relations} 占位符）。
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
		 * 构建摘要器。
		 *
		 * @return 摘要器
		 */
		public LlmCommunitySummarizer build() {
			Assert.notNull(this.chatClient, "chatClient 不能为 null");
			Assert.notBlank(this.model, "model 不能为空白");
			return new LlmCommunitySummarizer(this);
		}
	}
}
