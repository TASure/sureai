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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.prompt.PromptTemplate;
import com.sure.ai.rag.retriever.Retriever;
import com.sure.tool.lang.Assert;

/**
 * Corrective Retrieval（CRAG / Self-RAG 风格的纠正式检索器）。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>先用底层 {@link Retriever} 召回候选文档；</li>
 *   <li>用 LLM 对每个候选做相关性自检（Self-RAG 反思：输出「相关/不相关 + 一句理由」），
 *       解析容错；</li>
 *   <li><b>有相关文档</b>：过滤掉不相关文档，仅保留相关文档（保持原召回顺序）；</li>
 *   <li><b>全部不相关</b>：进入纠正分支——
 *     <ul>
 *       <li>注入了 {@link WebSearchProvider}：走网络搜索兜底，返回 web 结果；</li>
 *       <li>未注入：退化为「放宽召回」——用更大的候选量重检一次，并在返回文档的
 *           metadata 标注 {@code crag_degraded=true}，绝不编造 web 结果。</li>
 *     </ul>
 *   </li>
 *   <li>LLM 评估异常时保守保留全部候选（不误删）。</li>
 * </ol>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class CorrectiveRetriever implements Retriever {

	/** 默认相关性评估提示词模板，含 {query} 与 {document} 占位符。 */
	public static final String DEFAULT_EVAL_PROMPT_TEMPLATE =
			"你是检索质量评估器。请判断下面这段文档是否与用户查询相关。\n"
			+ "用户查询：{query}\n"
			+ "文档：{document}\n"
			+ "请先回答「相关」或「不相关」，再用一句话说明理由。";

	/** 默认 maxTokens。 */
	public static final int DEFAULT_MAX_TOKENS = 128;

	/** 降级重检时的候选量放大倍数。 */
	public static final int DEGRADED_RECALL_MULTIPLIER = 3;

	/** 标注文档为「降级重检」结果的 metadata key。 */
	public static final String META_DEGRADED = "crag_degraded";

	private final Retriever retriever;
	private final AiClient chatClient;
	private final String model;
	private final WebSearchProvider webSearchProvider;
	private final PromptTemplate evalPromptTemplate;
	private final int maxTokens;

	private CorrectiveRetriever(Builder builder) {
		this.retriever = builder.retriever;
		this.chatClient = builder.chatClient;
		this.model = builder.model;
		this.webSearchProvider = builder.webSearchProvider;
		this.evalPromptTemplate = builder.evalPromptTemplate == null
				? PromptTemplate.fromString(DEFAULT_EVAL_PROMPT_TEMPLATE) : builder.evalPromptTemplate;
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

		// 1. 召回候选
		List<Document> candidates = this.retriever.retrieve(query, topK);
		if (candidates == null || candidates.isEmpty()) {
			candidates = new ArrayList<>(0);
		}

		// 2. LLM 逐篇相关性自检；评估异常时保守保留该篇
		List<Document> relevant = new ArrayList<>(candidates.size());
		boolean evalFailedAll = true;
		for (Document doc : candidates) {
			Boolean isRelevant = assessRelevance(query, doc);
			if (isRelevant == null) {
				// 评估异常：保守保留
				relevant.add(doc);
				continue;
			}
			evalFailedAll = false;
			if (isRelevant) {
				relevant.add(doc);
			}
		}

		// 3. 评估整体异常（全部无法判断）→ 保留全部候选
		if (evalFailedAll && !candidates.isEmpty()) {
			return candidates;
		}

		// 4. 有相关文档 → 过滤后返回
		if (!relevant.isEmpty()) {
			return relevant;
		}

		// 5. 全部不相关 → 纠正分支
		if (this.webSearchProvider != null) {
			List<Document> webResults = this.webSearchProvider.search(query, topK);
			return webResults == null ? new ArrayList<>(0) : webResults;
		}
		// 无 web 提供者：放宽重检一次并标注
		return degradedReRetrieve(query, topK);
	}

	/**
	 * 用 LLM 评估单篇文档相关性。
	 *
	 * @param query 用户查询
	 * @param doc 候选文档
	 * @return true=相关，false=不相关，null=评估异常（调用方应保守保留）
	 */
	private Boolean assessRelevance(String query, Document doc) {
		try {
			Map<String, Object> vars = new HashMap<>();
			vars.put("query", query == null ? "" : query);
			vars.put("document", doc.text() == null ? "" : doc.text());
			String prompt = this.evalPromptTemplate.render(vars);
			ChatRequest request = ChatRequest.builder()
					.model(this.model)
					.messages(List.of(ChatMessage.user(prompt)))
					.maxTokens(this.maxTokens)
					.build();
			ChatResponse response = this.chatClient.chat(request);
			String answer = response == null ? null : response.firstText();
			return parseRelevance(answer);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * 容错解析 LLM 的相关性结论。
	 *
	 * @param answer LLM 回答
	 * @return true/false；无法判断时默认 false（由调用方在异常路径单独处理）
	 */
	private static boolean parseRelevance(String answer) {
		if (answer == null || answer.isBlank()) {
			return false;
		}
		String normalized = answer.trim().toLowerCase();
		// 明确「不相关/无关」一律视为不相关
		if (normalized.contains("不相关") || normalized.contains("无关")
				|| normalized.contains("not relevant") || normalized.contains("irrelevant")) {
			return false;
		}
		// 否则只要出现「相关/relevant」即视为相关
		return normalized.contains("相关") || normalized.contains("relevant");
	}

	/**
	 * 降级重检：用更大的候选量放宽召回，并标注 metadata。
	 *
	 * @param query 用户查询
	 * @param topK 目标条数
	 * @return 标注了降级标记的文档列表
	 */
	private List<Document> degradedReRetrieve(String query, int topK) {
		List<Document> broad = this.retriever.retrieve(query, topK * DEGRADED_RECALL_MULTIPLIER);
		if (broad == null || broad.isEmpty()) {
			return new ArrayList<>(0);
		}
		int limit = Math.min(topK, broad.size());
		List<Document> result = new ArrayList<>(limit);
		for (int i = 0; i < limit; i++) {
			Document doc = broad.get(i);
			Map<String, String> meta = new HashMap<>(doc.metadata());
			meta.put(META_DEGRADED, "true");
			result.add(Document.of(doc.id(), doc.text(), meta));
		}
		return result;
	}

	/**
	 * CorrectiveRetriever 构造器。
	 */
	public static final class Builder {

		private Retriever retriever;
		private AiClient chatClient;
		private String model;
		private WebSearchProvider webSearchProvider;
		private PromptTemplate evalPromptTemplate;
		private Integer maxTokens;

		private Builder() {
		}

		/**
		 * 设置底层召回检索器（必填）。
		 *
		 * @param retriever 底层检索器
		 * @return this
		 */
		public Builder retriever(Retriever retriever) {
			this.retriever = retriever;
			return this;
		}

		/**
		 * 设置相关性评估用对话客户端（必填）。
		 *
		 * @param chatClient 对话客户端
		 * @return this
		 */
		public Builder chatClient(AiClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		/**
		 * 设置评估模型名。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 注入网络搜索兜底提供者（可选）。
		 *
		 * @param webSearchProvider 网络搜索提供者
		 * @return this
		 */
		public Builder webSearchProvider(WebSearchProvider webSearchProvider) {
			this.webSearchProvider = webSearchProvider;
			return this;
		}

		/**
		 * 自定义相关性评估提示词模板（必须含 {query} 与 {document} 占位符）。
		 *
		 * @param evalPromptTemplate 提示词模板
		 * @return this
		 */
		public Builder evalPromptTemplate(PromptTemplate evalPromptTemplate) {
			this.evalPromptTemplate = evalPromptTemplate;
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
		 * 构建纠正式检索器。
		 *
		 * @return 检索器
		 */
		public CorrectiveRetriever build() {
			Assert.notNull(this.retriever, "retriever 不能为 null");
			Assert.notNull(this.chatClient, "chatClient 不能为 null");
			Assert.notBlank(this.model, "model 不能为空白");
			return new CorrectiveRetriever(this);
		}
	}
}
