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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.prompt.PromptTemplate;
import com.sure.tool.lang.Assert;

/**
 * 基于对话模型的实体/关系抽取器。
 *
 * <p>提示词要求模型每行输出一个三元组，格式严格为
 * {@code 主体 | 关系 | 客体}（竖线分隔三段）。该格式比 JSON 更易容错解析：
 * 实现侧按行拆分、按竖线切三段、跳过空行与段数不对/存在空段的脏行。</p>
 *
 * <p>容错策略：LLM 调用抛异常、返回空文本时不写入图谱、不向上抛出；
 * 单个三元组解析失败仅跳过该行，不影响其余三元组。自环（主体==客体）由
 * {@link KnowledgeGraph#addRelation} 负责丢弃。每个实体/关系的元数据均记录来源文档 id。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class LlmEntityRelationExtractor implements EntityRelationExtractor {

	/** 默认抽取提示词模板，含 {text} 占位符。 */
	public static final String DEFAULT_PROMPT_TEMPLATE =
			"从下面的文本中抽取命名实体及其之间的关系，每行输出一个三元组，"
			+ "格式严格为：主体 | 关系 | 客体\n"
			+ "要求：仅输出三元组，用半角竖线 | 分隔三段，不要编号、不要解释、不要空行；"
			+ "实体类别可不输出。若文本中没有可抽取的关系则不输出任何内容。\n"
			+ "文本：\n{text}";

	/** 默认 maxTokens。 */
	public static final int DEFAULT_MAX_TOKENS = 1024;

	/** 三元组分隔符。 */
	private static final String SEPARATOR = "\\|";

	private final AiClient chatClient;
	private final String model;
	private final PromptTemplate promptTemplate;
	private final int maxTokens;

	private LlmEntityRelationExtractor(Builder builder) {
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
	public void extractInto(Document doc, KnowledgeGraph graph) {
		Assert.notNull(doc, "doc 不能为 null");
		Assert.notNull(graph, "graph 不能为 null");

		String text = doc.text() == null ? "" : doc.text();
		String raw;
		try {
			String prompt = this.promptTemplate.render(Map.of("text", text));
			ChatRequest request = ChatRequest.builder()
					.model(this.model)
					.messages(List.of(ChatMessage.user(prompt)))
					.maxTokens(this.maxTokens)
					.build();
			ChatResponse response = this.chatClient.chat(request);
			raw = response == null ? null : response.firstText();
		} catch (RuntimeException e) {
			// LLM 异常：不写入、不向上抛，索引链路继续
			return;
		}
		if (raw == null || raw.isBlank()) {
			return;
		}

		Map<String, String> sourceMeta = new LinkedHashMap<>();
		sourceMeta.put(KnowledgeGraph.META_SOURCE_DOC_ID, doc.id());

		for (String line : raw.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String[] parts = trimmed.split(SEPARATOR);
			if (parts.length != 3) {
				// 格式错误行：跳过
				continue;
			}
			String subject = parts[0].trim();
			String relation = parts[1].trim();
			String object = parts[2].trim();
			if (subject.isEmpty() || relation.isEmpty() || object.isEmpty()) {
				continue;
			}
			graph.addEntity(subject, null, sourceMeta);
			graph.addEntity(object, null, sourceMeta);
			graph.addRelation(subject, object, relation, sourceMeta);
		}
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
		 * 自定义抽取提示词模板（必须含 {text} 占位符）。
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
		 * 构建抽取器。
		 *
		 * @return 抽取器
		 */
		public LlmEntityRelationExtractor build() {
			Assert.notNull(this.chatClient, "chatClient 不能为 null");
			Assert.notBlank(this.model, "model 不能为空白");
			return new LlmEntityRelationExtractor(this);
		}
	}
}
