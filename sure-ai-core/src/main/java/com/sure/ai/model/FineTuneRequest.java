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
 * 微调任务请求。
 *
 * <p>不可变，通过 {@link #builder()} 构造；model 与 trainingFileId 为必填。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class FineTuneRequest {

	private final String model;
	private final String trainingFileId;
	private final Object hyperparameters;
	private final String suffix;
	private final Map<String, Object> extra;

	private FineTuneRequest(Builder b) {
		this.model = b.model;
		this.trainingFileId = b.trainingFileId;
		this.hyperparameters = b.hyperparameters;
		this.suffix = b.suffix;
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
		private String trainingFileId;
		private Object hyperparameters;
		private String suffix;
		private Map<String, Object> extra;

		private Builder() {
		}

		/**
		 * 基础模型。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 训练文件 ID（已上传的训练数据文件）。
		 *
		 * @param trainingFileId 训练文件 ID
		 * @return this
		 */
		public Builder trainingFileId(String trainingFileId) {
			this.trainingFileId = trainingFileId;
			return this;
		}

		/**
		 * 超参数（平台差异透传：epoch_size/learning_rate/multiplier 等）。
		 *
		 * @param hyperparameters 超参数
		 * @return this
		 */
		public Builder hyperparameters(Object hyperparameters) {
			this.hyperparameters = hyperparameters;
			return this;
		}

		/**
		 * 微调模型名后缀。
		 *
		 * @param suffix 后缀
		 * @return this
		 */
		public Builder suffix(String suffix) {
			this.suffix = suffix;
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
		public FineTuneRequest build() {
			Assert.notBlank(this.model, "model must not be blank");
			Assert.notBlank(this.trainingFileId, "trainingFileId must not be blank");
			return new FineTuneRequest(this);
		}
	}

	/** 基础模型名。 */
	public String model() {
		return this.model;
	}

	/** 训练文件 ID。 */
	public String trainingFileId() {
		return this.trainingFileId;
	}

	/** 超参数，未设置时为 null。 */
	public Object hyperparameters() {
		return this.hyperparameters;
	}

	/** 微调模型名后缀，未设置时为 null。 */
	public String suffix() {
		return this.suffix;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
