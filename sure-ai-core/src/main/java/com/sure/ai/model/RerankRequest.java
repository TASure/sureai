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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.tool.lang.Assert;

/**
 * 重排（Rerank）请求。
 *
 * <p>将候选文档集合与查询送入重排模型，按相关性重新排序。
 * model / query / documents 为必填。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class RerankRequest {

	private final String model;
	private final String query;
	private final List<String> documents;
	private final Integer topN;
	private final Map<String, Object> extra;

	private RerankRequest(Builder b) {
		this.model = b.model;
		this.query = b.query;
		this.documents = List.copyOf(b.documents);
		this.topN = b.topN;
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
		private String query;
		private List<String> documents;
		private Integer topN;
		private Map<String, Object> extra;

		private Builder() {
		}

		/**
		 * 设置重排模型。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 设置查询文本。
		 *
		 * @param query 查询
		 * @return this
		 */
		public Builder query(String query) {
			this.query = query;
			return this;
		}

		/**
		 * 设置候选文档列表。
		 *
		 * @param documents 候选文档
		 * @return this
		 */
		public Builder documents(List<String> documents) {
			this.documents = documents;
			return this;
		}

		/**
		 * 追加候选文档。
		 *
		 * @param document 候选文档
		 * @return this
		 */
		public Builder addDocument(String document) {
			if (this.documents == null) {
				this.documents = new ArrayList<>();
			}
			this.documents.add(document);
			return this;
		}

		/**
		 * 设置返回 topN 条最相关结果。
		 *
		 * @param topN 返回条数
		 * @return this
		 */
		public Builder topN(Integer topN) {
			this.topN = topN;
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
		public RerankRequest build() {
			Assert.notBlank(this.model, "model 不能为 blank");
			Assert.notBlank(this.query, "query 不能为 blank");
			Assert.notEmpty(this.documents, "documents 不能为空");
			return new RerankRequest(this);
		}
	}

	/** 重排模型名。 */
	public String model() {
		return this.model;
	}

	/** 查询文本。 */
	public String query() {
		return this.query;
	}

	/** 候选文档列表（不可变）。 */
	public List<String> documents() {
		return this.documents;
	}

	/** 返回 topN 条结果，未设置时为 null。 */
	public Integer topN() {
		return this.topN;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
