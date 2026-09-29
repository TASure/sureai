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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sure.ai.rag.model.Document;
import com.sure.tool.lang.Assert;

/**
 * 一次 RAG 问答的可评估轨迹（trace）。
 *
 * <p>记录问题、模型答案、检索到的上下文（保持检索排序）、可选的参考答案（ground truth，
 * 供 context recall 使用）以及附加元数据。轨迹可被 {@code TraceSerializer} 序列化为 JSON，
 * 用于离线回放与跨版本回归对比。</p>
 *
 * <p>不可变：{@code contexts} 与 {@code metadata} 在构造时做防御性拷贝，访问器返回不可变视图。</p>
 *
 * @param traceId 轨迹唯一标识，用于存储与回放
 * @param question 用户问题
 * @param answer 模型生成的答案
 * @param contexts 检索到的上下文文档，保持检索排序
 * @param referenceAnswer 参考答案（ground truth），可为 {@code null}（无则 context recall 标记为不适用）
 * @param metadata 附加元数据（模型名、版本、耗时等），不可为 null
 * @author sureai
 * @since 1.8.0
 */
public record RagTrace(String traceId, String question, String answer,
		List<Document> contexts, String referenceAnswer, Map<String, Object> metadata) {

	/**
	 * 紧凑构造器：防御性拷贝上下文与元数据。
	 */
	public RagTrace {
		Assert.notBlank(traceId, "traceId 不能为空白");
		Assert.notNull(question, "question 不能为 null");
		Assert.notNull(answer, "answer 不能为 null");
		contexts = contexts == null ? List.of() : List.copyOf(contexts);
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}

	/**
	 * 便捷构造：无参考答案、无元数据，自动生成 traceId。
	 *
	 * @param question 问题
	 * @param answer 答案
	 * @param contexts 上下文
	 * @return 轨迹
	 */
	public static RagTrace of(String question, String answer, List<Document> contexts) {
		return builder().question(question).answer(answer).contexts(contexts).build();
	}

	/**
	 * 便捷构造：带参考答案，无元数据，自动生成 traceId。
	 *
	 * @param question 问题
	 * @param answer 答案
	 * @param contexts 上下文
	 * @param referenceAnswer 参考答案
	 * @return 轨迹
	 */
	public static RagTrace of(String question, String answer, List<Document> contexts,
			String referenceAnswer) {
		return builder().question(question).answer(answer).contexts(contexts)
				.referenceAnswer(referenceAnswer).build();
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

		private String traceId;
		private String question;
		private String answer;
		private List<Document> contexts;
		private String referenceAnswer;
		private final Map<String, Object> metadata = new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * 设置轨迹 ID（未设置则自动生成）。
		 *
		 * @param traceId 轨迹 ID
		 * @return this
		 */
		public Builder traceId(String traceId) {
			this.traceId = traceId;
			return this;
		}

		/**
		 * 设置问题。
		 *
		 * @param question 问题
		 * @return this
		 */
		public Builder question(String question) {
			this.question = question;
			return this;
		}

		/**
		 * 设置答案。
		 *
		 * @param answer 答案
		 * @return this
		 */
		public Builder answer(String answer) {
			this.answer = answer;
			return this;
		}

		/**
		 * 设置上下文列表（保持排序）。
		 *
		 * @param contexts 上下文
		 * @return this
		 */
		public Builder contexts(List<Document> contexts) {
			this.contexts = contexts == null ? List.of() : new ArrayList<>(contexts);
			return this;
		}

		/**
		 * 设置参考答案。
		 *
		 * @param referenceAnswer 参考答案
		 * @return this
		 */
		public Builder referenceAnswer(String referenceAnswer) {
			this.referenceAnswer = referenceAnswer;
			return this;
		}

		/**
		 * 追加一条元数据。
		 *
		 * @param key 键
		 * @param value 值
		 * @return this
		 */
		public Builder metadata(String key, Object value) {
			this.metadata.put(key, value);
			return this;
		}

		/**
		 * 批量设置元数据。
		 *
		 * @param metadata 元数据
		 * @return this
		 */
		public Builder metadata(Map<String, Object> metadata) {
			if (metadata != null) {
				this.metadata.putAll(metadata);
			}
			return this;
		}

		/**
		 * 构建轨迹，校验必填字段。
		 *
		 * @return 轨迹
		 */
		public RagTrace build() {
			Assert.notNull(this.question, "question 不能为 null");
			Assert.notNull(this.answer, "answer 不能为 null");
			String id = (this.traceId == null || this.traceId.isBlank())
					? "trace-" + UUID.randomUUID() : this.traceId;
			return new RagTrace(id, this.question, this.answer, this.contexts,
					this.referenceAnswer, this.metadata);
		}
	}
}
