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

package com.sure.ai.rag.evaluation;

import java.util.List;

import com.sure.ai.client.AiClient;
import com.sure.ai.rag.model.Document;

/**
 * 忠实度（faithfulness）：衡量答案中的陈述能否被检索上下文支持。
 *
 * <p>口径（RAGAS）：</p>
 * <ol>
 *   <li>让 LLM 从答案抽取若干原子陈述（claims，每行一条）；</li>
 *   <li>逐条让 LLM 判断该陈述能否被给定上下文支持（YES/NO）；</li>
 *   <li>{@code faithfulness = 被支持的 claim 数 / claim 总数}。</li>
 * </ol>
 *
 * <p>容错约定：</p>
 * <ul>
 *   <li>答案无法抽取出任何 claim 时返回 {@code 1.0}（无可证伪陈述，视为忠实）；</li>
 *   <li>单条 claim 的 YES/NO 解析失败时保守判为「不支持」（不计入分子）；</li>
 *   <li>模型调用异常时 {@link LlmJudge#ask(String)} 返回 null，按不支持处理。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class FaithfulnessMetric extends LlmJudge implements RagMetric {

	/** claim 抽取提示词模板。 */
	public static final String CLAIM_EXTRACTION_PROMPT =
			"请从下面的回答中抽取所有可独立判断的原子事实陈述，每条一行，"
			+ "不要编号、不要解释、不要重复：\n回答：{answer}";

	/** claim 支持判定提示词模板。 */
	public static final String SUPPORT_JUDGE_PROMPT =
			"请判断下面这条陈述能否完全由给定的上下文所支持。只回答 YES 或 NO。\n"
			+ "陈述：{claim}\n上下文：\n{contexts}";

	private static final String NAME = "faithfulness";

	private FaithfulnessMetric(Builder builder) {
		super(builder.chatClient, builder.model, builder.maxTokens);
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
	public String name() {
		return NAME;
	}

	@Override
	public double evaluate(RagTrace trace) {
		List<String> claims = LlmJudge.parseLineItems(ask(CLAIM_EXTRACTION_PROMPT
				.replace("{answer}", trace.answer())));
		if (claims.isEmpty()) {
			return 1.0;
		}
		String contextText = formatContexts(trace.contexts());
		int supported = 0;
		for (String claim : claims) {
			String prompt = SUPPORT_JUDGE_PROMPT
					.replace("{claim}", claim)
					.replace("{contexts}", contextText);
			// 解析失败保守判为不支持（false）
			if (LlmJudge.parseYesNo(ask(prompt), false)) {
				supported++;
			}
		}
		return (double) supported / claims.size();
	}

	/**
	 * 将上下文文档列表拼接为编号文本块。
	 *
	 * @param contexts 上下文
	 * @return 拼接文本
	 */
	static String formatContexts(List<Document> contexts) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < contexts.size(); i++) {
			Document doc = contexts.get(i);
			sb.append("[文档").append(i + 1).append("] ").append(doc.text());
			if (i < contexts.size() - 1) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private AiClient chatClient;
		private String model;
		private int maxTokens;

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
		 * 构建指标。
		 *
		 * @return 指标
		 */
		public FaithfulnessMetric build() {
			return new FaithfulnessMetric(this);
		}
	}
}
