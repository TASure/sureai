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
import java.util.Map;

import com.sure.tool.lang.Assert;

/**
 * 内容审核请求。
 *
 * <p>不可变，通过 {@link #builder()} 构造；input 为必填，model 缺省为
 * {@code text-moderation-latest}。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class ModerationRequest {

	/** 默认审核模型。 */
	public static final String DEFAULT_MODEL = "text-moderation-latest";

	private final String model;
	private final String input;
	private final Map<String, Object> extra;

	private ModerationRequest(Builder b) {
		this.model = b.model;
		this.input = b.input;
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
	 * 便捷构造。
	 *
	 * @param input 待审核文本
	 * @return 请求
	 */
	public static ModerationRequest of(String input) {
		return builder().input(input).build();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String model;
		private String input;
		private Map<String, Object> extra;

		private Builder() {
		}

		/**
		 * 审核模型（缺省 text-moderation-latest）。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 待审核文本。
		 *
		 * @param input 文本
		 * @return this
		 */
		public Builder input(String input) {
			this.input = input;
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
		 * 构建请求，校验必填字段；model 为空时填默认值。
		 *
		 * @return 请求
		 */
		public ModerationRequest build() {
			Assert.notBlank(this.input, "input must not be blank");
			if (this.model == null || this.model.isBlank()) {
				this.model = DEFAULT_MODEL;
			}
			return new ModerationRequest(this);
		}
	}

	/** 审核模型名。 */
	public String model() {
		return this.model;
	}

	/** 待审核文本。 */
	public String input() {
		return this.input;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
