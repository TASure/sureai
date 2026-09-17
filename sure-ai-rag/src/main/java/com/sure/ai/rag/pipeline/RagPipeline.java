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

package com.sure.ai.rag.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.embedding.ClientEmbeddingProvider;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.model.SimilaritySearchResult;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.ai.rag.retriever.VectorRetriever;
import com.sure.ai.rag.splitter.RecursiveCharacterTextSplitter;
import com.sure.ai.rag.splitter.TextSplitter;
import com.sure.ai.rag.store.InMemoryVectorStore;
import com.sure.ai.rag.store.VectorStore;
import com.sure.tool.lang.Assert;

/**
 * RAG（检索增强生成）管线：索引 → 检索 → 增强 → 生成 的端到端编排。
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li><b>索引</b>：{@link #ingest(String, String)} 将长文本切分为分块并向量化写入向量库；</li>
 *   <li><b>检索</b>：{@link #retrieve(String, int)} 将查询向量化后在向量库中做相似度检索；</li>
 *   <li><b>增强</b>：{@link #ask(String)} 将命中文档拼入系统提示词上下文；</li>
 *   <li><b>生成</b>：携带上下文调用对话客户端，返回增强后的回答。</li>
 * </ol>
 *
 * <p>对话与向量化客户端均可复用 sureai 任一平台模块的客户端实例，
 * 管线本身不绑定具体平台。默认使用进程内向量库与递归字符分块器，
 * 亦可通过 Builder 替换为外部向量库与自定义分块/检索策略。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class RagPipeline {

	/** 默认检索返回条数 */
	public static final int DEFAULT_TOP_K = 4;
	/** 默认系统提示词模板，{@code {context}} 会被检索上下文替换 */
	public static final String DEFAULT_SYSTEM_PROMPT_TEMPLATE =
			"你是知识库问答助手。请仅根据下面提供的上下文回答用户问题；"
			+ "若上下文不足以回答，请明确说明信息不足，不要编造。\n\n"
			+ "上下文：\n{context}";

	private final AiClient chatClient;
	private final String chatModel;
	private final EmbeddingProvider embeddingProvider;
	private final VectorStore vectorStore;
	private final TextSplitter splitter;
	private final Retriever retriever;
	private final String systemPromptTemplate;
	private final int defaultTopK;

	private RagPipeline(Builder builder) {
		this.chatClient = builder.chatClient;
		this.chatModel = builder.chatModel;
		this.embeddingProvider = builder.embeddingProvider;
		this.vectorStore = builder.vectorStore;
		this.splitter = builder.splitter;
		this.retriever = builder.retriever;
		this.systemPromptTemplate = builder.systemPromptTemplate;
		this.defaultTopK = builder.defaultTopK;
	}
	/**
	 * 创建管线 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 返回对话客户端。
	 *
	 * @return 对话客户端
	 */
	public AiClient chatClient() {
		return chatClient;
	}

	/**
	 * 返回对话模型 ID。
	 *
	 * @return 对话模型 ID
	 */
	public String chatModel() {
		return chatModel;
	}

	/**
	 * 返回向量化实现。
	 *
	 * @return 向量化实现
	 */
	public EmbeddingProvider embeddingProvider() {
		return embeddingProvider;
	}

	/**
	 * 返回向量存储。
	 *
	 * @return 向量存储
	 */
	public VectorStore vectorStore() {
		return vectorStore;
	}

	/**
	 * 返回文本分块器。
	 *
	 * @return 文本分块器
	 */
	public TextSplitter splitter() {
		return splitter;
	}

	/**
	 * 返回检索器。
	 *
	 * @return 检索器
	 */
	public Retriever retriever() {
		return retriever;
	}

	/**
	 * 将长文本切分、向量化并写入向量库。
	 *
	 * <p>等价于以 {@code sourceId#i} 为文档 id 逐块入库。</p>
	 *
	 * @param sourceId 来源标识
	 * @param text 原始长文本
	 * @return 入库的分块数
	 */
	public int ingest(String sourceId, String text) {
		Assert.notNull(sourceId, "sourceId 不能为 null");
		List<Document> documents = splitter.splitToDocuments(sourceId, text);
		return ingestDocuments(documents);
	}

	/**
	 * 将带元数据的文档切分、向量化并写入向量库。
	 *
	 * <p>文档元数据（来源、标题等）会随分块一并写入，检索时原样带回。</p>
	 *
	 * @param document 原始文档
	 * @return 入库的分块数
	 */
	public int ingest(Document document) {
		Assert.notNull(document, "document 不能为 null");
		List<String> chunks = splitter.split(document.text());
		List<Document> documents = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			documents.add(Document.of(document.id() + "#" + i, chunks.get(i),
					document.metadata()));
		}
		return ingestDocuments(documents);
	}

	/**
	 * 直接入库一组已切分好的文档（跳过再次切分）。
	 *
	 * @param documents 文档列表，可为空
	 * @return 入库的文档数
	 */
	public int ingestDocuments(List<Document> documents) {
		if (documents == null || documents.isEmpty()) {
			return 0;
		}
		List<String> texts = new ArrayList<>(documents.size());
		for (Document document : documents) {
			texts.add(document.text());
		}
		List<float[]> embeddings = embeddingProvider.embedAll(texts);
		Assert.isTrue(embeddings.size() == documents.size(),
				"向量化数量与文档数量不一致，期望 {} 实际 {}",
				documents.size(), embeddings.size());
		List<com.sure.ai.rag.model.Vector> vectors = new ArrayList<>(documents.size());
		for (int i = 0; i < documents.size(); i++) {
			Document document = documents.get(i);
			vectors.add(com.sure.ai.rag.model.Vector.of(
					document.id(), embeddings.get(i), document.text(), document.metadata()));
		}
		vectorStore.addAll(vectors);
		return vectors.size();
	}

	/**
	 * 检索与查询最相关的文档（不含得分）。
	 *
	 * @param query 用户查询
	 * @param topK 返回条数
	 * @return 相关文档列表
	 */
	public List<Document> retrieve(String query, int topK) {
		return retriever.retrieve(query, topK);
	}

	/**
	 * 检索与查询最相关的文档（含相似度得分）。
	 *
	 * @param query 用户查询
	 * @param topK 返回条数
	 * @return 带得分的检索结果
	 */
	public List<SimilaritySearchResult> retrieveWithScores(String query, int topK) {
		Assert.isTrue(retriever instanceof VectorRetriever,
				"仅 VectorRetriever 支持带得分检索，当前检索器为 {}", retriever.getClass().getName());
		return ((VectorRetriever) retriever).retrieveWithScores(query, topK);
	}

	/**
	 * 检索增强问答：检索 topK 默认值 {@link #DEFAULT_TOP_K} 条上下文并生成回答。
	 *
	 * @param question 用户问题
	 * @return 对话响应
	 */
	public ChatResponse ask(String question) {
		return ask(question, defaultTopK);
	}

	/**
	 * 检索增强问答：检索指定条数上下文并生成回答。
	 *
	 * @param question 用户问题
	 * @param topK 检索条数，必须大于 0
	 * @return 对话响应
	 */
	public ChatResponse ask(String question, int topK) {
		Assert.notNull(question, "question 不能为 null");
		Assert.isTrue(topK > 0, "topK 必须大于 0，实际为 {}", topK);
		List<Document> documents = retrieve(question, topK);
		String context = buildContext(documents);
		String systemPrompt = systemPromptTemplate.replace("{context}", context);
		ChatRequest request = ChatRequest.builder()
				.model(chatModel)
				.messages(List.of(
						ChatMessage.system(systemPrompt),
						ChatMessage.user(question)))
				.build();
		return chatClient.chat(request);
	}

	/**
	 * 将命中文档拼装为上下文文本。
	 *
	 * @param documents 命中文档
	 * @return 上下文文本（无命中时返回空串）
	 */
	private String buildContext(List<Document> documents) {
		if (documents == null || documents.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < documents.size(); i++) {
			Document document = documents.get(i);
			sb.append('[').append(i + 1).append("] ").append(document.text()).append('\n');
		}
		return sb.toString();
	}

	/**
	 * RagPipeline 构造器。
	 *
	 * <p>必填：{@code chatClient}、{@code chatModel}、{@code embeddingClient}、
	 * {@code embeddingModel}。其余项均有默认值，可按需覆盖。</p>
	 */
	public static final class Builder {

		private AiClient chatClient;
		private String chatModel;
		private EmbeddingClient embeddingClient;
		private String embeddingModel;
		private EmbeddingProvider embeddingProvider;
		private VectorStore vectorStore = new InMemoryVectorStore();
		private TextSplitter splitter = RecursiveCharacterTextSplitter.createDefault();
		private Retriever retriever;
		private String systemPromptTemplate = DEFAULT_SYSTEM_PROMPT_TEMPLATE;
		private int defaultTopK = DEFAULT_TOP_K;
		private double minScore = Double.NEGATIVE_INFINITY;

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
		 * 设置对话模型 ID。
		 *
		 * @param chatModel 对话模型 ID
		 * @return this
		 */
		public Builder chatModel(String chatModel) {
			this.chatModel = chatModel;
			return this;
		}

		/**
		 * 设置向量化客户端。
		 *
		 * @param embeddingClient 嵌入客户端
		 * @return this
		 */
		public Builder embeddingClient(EmbeddingClient embeddingClient) {
			this.embeddingClient = embeddingClient;
			return this;
		}

		/**
		 * 设置向量化模型 ID。
		 *
		 * @param embeddingModel 嵌入模型 ID
		 * @return this
		 */
		public Builder embeddingModel(String embeddingModel) {
			this.embeddingModel = embeddingModel;
			return this;
		}

		/**
		 * 覆盖向量存储（默认进程内 {@link InMemoryVectorStore}）。
		 *
		 * @param vectorStore 向量存储
		 * @return this
		 */
		public Builder vectorStore(VectorStore vectorStore) {
			this.vectorStore = vectorStore;
			return this;
		}

		/**
		 * 覆盖文本分块器（默认递归字符分块器）。
		 *
		 * @param splitter 文本分块器
		 * @return this
		 */
		public Builder splitter(TextSplitter splitter) {
			this.splitter = splitter;
			return this;
		}

		/**
		 * 覆盖检索器（默认向量检索器）。
		 *
		 * @param retriever 检索器
		 * @return this
		 */
		public Builder retriever(Retriever retriever) {
			this.retriever = retriever;
			return this;
		}

		/**
		 * 覆盖系统提示词模板，占位符 {@code {context}} 会被检索上下文替换。
		 *
		 * @param template 提示词模板
		 * @return this
		 */
		public Builder systemPromptTemplate(String template) {
			this.systemPromptTemplate = template;
			return this;
		}

		/**
		 * 设置默认检索条数（调用 {@link #ask(String)} 时生效）。
		 *
		 * @param topK 检索条数
		 * @return this
		 */
		public Builder defaultTopK(int topK) {
			this.defaultTopK = topK;
			return this;
		}

		/**
		 * 设置检索相似度阈值（仅默认向量检索器生效）。
		 *
		 * @param minScore 最低相似度阈值
		 * @return this
		 */
		public Builder minScore(double minScore) {
			this.minScore = minScore;
			return this;
		}

		/**
		 * 构建管线。
		 *
		 * @return RagPipeline
		 */
		public RagPipeline build() {
			Assert.notNull(chatClient, "chatClient 不能为 null");
			Assert.notBlank(chatModel, "chatModel 不能为空");
			Assert.notNull(embeddingClient, "embeddingClient 不能为 null");
			Assert.notBlank(embeddingModel, "embeddingModel 不能为空");
			Assert.notNull(vectorStore, "vectorStore 不能为 null");
			Assert.notNull(splitter, "splitter 不能为 null");
			Assert.isTrue(defaultTopK > 0, "defaultTopK 必须大于 0，实际为 {}", defaultTopK);
			this.embeddingProvider = new ClientEmbeddingProvider(embeddingClient,
					embeddingModel);
			if (retriever == null) {
				this.retriever = new VectorRetriever(vectorStore, embeddingProvider, minScore);
			}
			return new RagPipeline(this);
		}
	}

	/**
	 * 返回构造参数摘要（用于日志与调试）。
	 *
	 * @return 参数摘要
	 */
	public Map<String, Object> describe() {
		return Map.of(
				"chatModel", chatModel,
				"vectorStore", vectorStore.getClass().getSimpleName(),
				"splitter", splitter.getClass().getSimpleName(),
				"retriever", retriever.getClass().getSimpleName(),
				"defaultTopK", defaultTopK);
	}
}
