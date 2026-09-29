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

package com.sure.ai.rag.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.prompt.PromptTemplate;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.store.VectorStore;
import com.sure.tool.lang.Assert;

/**
 * HyDE（Hypothetical Document Embeddings，假设文档嵌入）检索器。
 *
 * <p>核心思想：用户原始查询往往是简短的提问，与知识库中陈述式的文档在表述上存在
 * 分布差异。HyDE 先用 LLM 针对查询生成一段「假设答案」（哪怕是幻觉、未必正确），
 * 再把这段假设答案向量化去检索——因为假设答案是陈述式文本，与真实文档在向量空间中
 * 更接近，从而提升召回率。</p>
 *
 * <p>流程：</p>
 * <ol>
 *   <li>用专门提示词（默认「请直接回答以下问题，无需解释」）让 LLM 生成假设答案；</li>
 *   <li>仅用假设答案文本（默认不拼接原 query）做向量化；</li>
 *   <li>在底层 {@link VectorStore} 上做相似度检索并映射为 {@link Document}。</li>
 * </ol>
 *
 * <p>容错策略：LLM 调用抛异常、返回空文本时，回退为用原始 query 直接向量化检索，
 * 绝不向上抛出，保证检索链路可用。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class HydeRetriever implements Retriever {

	/** 默认假设答案提示词模板，含 {query} 占位符。 */
	public static final String DEFAULT_PROMPT_TEMPLATE =
			"请直接回答以下问题，无需解释，无需任何前言或后记：\n{query}";

	/** 默认 maxTokens。 */
	public static final int DEFAULT_MAX_TOKENS = 256;

	private final AiClient chatClient;
	private final String model;
	private final EmbeddingProvider embeddingProvider;
	private final VectorStore store;
	private final PromptTemplate promptTemplate;
	private final double minScore;
	private final int maxTokens;

	private HydeRetriever(Builder builder) {
		this.chatClient = builder.chatClient;
		this.model = builder.model;
		this.embeddingProvider = builder.embeddingProvider;
		this.store = builder.store;
		this.promptTemplate = builder.promptTemplate == null
				? PromptTemplate.fromString(DEFAULT_PROMPT_TEMPLATE) : builder.promptTemplate;
		this.minScore = builder.minScore;
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
	public List<Document> retrieve(String query, int topK) {
		Assert.notNull(query, "query 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);

		// 1. 生成假设答案；失败回退 null（后续用原 query）
		String hypothetical = generateHypothetical(query);
		// 2. 决定实际用于向量化的文本：优先假设答案，空/失败时用原 query
		String embedText = (hypothetical == null || hypothetical.isBlank()) ? query : hypothetical;

		// 3. 向量化并检索
		float[] embedding = this.embeddingProvider.embed(embedText);
		List<SimilaritySearchResult> results = this.store.similaritySearch(embedding, topK, this.minScore);
		List<Document> documents = new ArrayList<>(results.size());
		for (SimilaritySearchResult result : results) {
			documents.add(Document.of(result.id(), result.text(), result.metadata()));
		}
		return documents;
	}

	/**
	 * 让 LLM 生成假设答案。任何异常均吞掉并返回 null，由调用方回退。
	 *
	 * @param query 原始查询
	 * @return 假设答案文本，失败返回 null
	 */
	private String generateHypothetical(String query) {
		try {
			String prompt = this.promptTemplate.render(Map.of("query", query == null ? "" : query));
			ChatRequest request = ChatRequest.builder()
					.model(this.model)
					.messages(List.of(ChatMessage.user(prompt)))
					.maxTokens(this.maxTokens)
					.build();
			ChatResponse response = this.chatClient.chat(request);
			return response == null ? null : response.firstText();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * HydeRetriever 构造器。
	 */
	public static final class Builder {

		private AiClient chatClient;
		private String model;
		private EmbeddingProvider embeddingProvider;
		private VectorStore store;
		private PromptTemplate promptTemplate;
		private double minScore = Double.NEGATIVE_INFINITY;
		private Integer maxTokens;

		private Builder() {
		}

		/**
		 * 设置对话客户端（用于生成假设答案）。
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
		 * 设置向量化实现（对假设答案/原 query 向量化）。
		 *
		 * @param embeddingProvider 向量化实现
		 * @return this
		 */
		public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
			this.embeddingProvider = embeddingProvider;
			return this;
		}

		/**
		 * 设置底层向量存储。
		 *
		 * @param store 向量存储
		 * @return this
		 */
		public Builder store(VectorStore store) {
			this.store = store;
			return this;
		}

		/**
		 * 自定义假设答案提示词模板（必须含 {query} 占位符）。
		 *
		 * @param promptTemplate 提示词模板
		 * @return this
		 */
		public Builder promptTemplate(PromptTemplate promptTemplate) {
			this.promptTemplate = promptTemplate;
			return this;
		}

		/**
		 * 设置最低相似度阈值。
		 *
		 * @param minScore 阈值
		 * @return this
		 */
		public Builder minScore(double minScore) {
			this.minScore = minScore;
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
		 * 构建 HyDE 检索器。
		 *
		 * @return 检索器
		 */
		public HydeRetriever build() {
			Assert.notNull(this.chatClient, "chatClient 不能为 null");
			Assert.notBlank(this.model, "model 不能为空白");
			Assert.notNull(this.embeddingProvider, "embeddingProvider 不能为 null");
			Assert.notNull(this.store, "store 不能为 null");
			return new HydeRetriever(this);
		}
	}
}
