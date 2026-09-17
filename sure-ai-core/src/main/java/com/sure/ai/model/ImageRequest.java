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
 * 图像生成请求。
 *
 * <p>不可变，通过 {@link #builder()} 构造；{@code model} 与 {@code prompt} 为必填。
 * 平台不支持的字段（如 quality / style）在序列化时自动忽略。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class ImageRequest {

	/** 响应格式：URL。 */
	public static final String RESPONSE_FORMAT_URL = "url";

	/** 响应格式：Base64 JSON。 */
	public static final String RESPONSE_FORMAT_B64_JSON = "b64_json";

	private final String model;
	private final String prompt;
	private final Integer n;
	private final String size;
	private final String quality;
	private final String style;
	private final String responseFormat;
	private final String user;
	private final Map<String, Object> extra;

	private ImageRequest(Builder b) {
		this.model = b.model;
		this.prompt = b.prompt;
		this.n = b.n;
		this.size = b.size;
		this.quality = b.quality;
		this.style = b.style;
		this.responseFormat = b.responseFormat;
		this.user = b.user;
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
	 * 便捷构造：仅模型与提示词。
	 *
	 * @param model  模型名
	 * @param prompt 提示词
	 * @return 请求
	 */
	public static ImageRequest of(String model, String prompt) {
		return builder().model(model).prompt(prompt).build();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String model;
		private String prompt;
		private Integer n;
		private String size;
		private String quality;
		private String style;
		private String responseFormat;
		private String user;
		private Map<String, Object> extra;

		private Builder() {
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
		 * 设置提示词。
		 *
		 * @param prompt 提示词
		 * @return this
		 */
		public Builder prompt(String prompt) {
			this.prompt = prompt;
			return this;
		}

		/**
		 * 设置生成图像数量。
		 *
		 * @param n 数量
		 * @return this
		 */
		public Builder n(Integer n) {
			this.n = n;
			return this;
		}

		/**
		 * 设置图像尺寸（如 "1024x1024"、"1024*1024"）。
		 *
		 * @param size 尺寸
		 * @return this
		 */
		public Builder size(String size) {
			this.size = size;
			return this;
		}

		/**
		 * 设置质量（如 "standard"、"hd"）。
		 *
		 * @param quality 质量
		 * @return this
		 */
		public Builder quality(String quality) {
			this.quality = quality;
			return this;
		}

		/**
		 * 设置风格（如 "vivid"、"natural"）。
		 *
		 * @param style 风格
		 * @return this
		 */
		public Builder style(String style) {
			this.style = style;
			return this;
		}

		/**
		 * 设置响应格式（"url" 或 "b64_json"）。
		 *
		 * @param responseFormat 响应格式
		 * @return this
		 */
		public Builder responseFormat(String responseFormat) {
			this.responseFormat = responseFormat;
			return this;
		}

		/**
		 * 设置用户标识。
		 *
		 * @param user 用户标识
		 * @return this
		 */
		public Builder user(String user) {
			this.user = user;
			return this;
		}

		/**
		 * 追加透传字段（平台特定参数）。
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
		public ImageRequest build() {
			Assert.notBlank(this.model, "model must not be blank");
			Assert.notBlank(this.prompt, "prompt must not be blank");
			return new ImageRequest(this);
		}
	}

	/** 模型名。 */
	public String model() {
		return this.model;
	}

	/** 提示词。 */
	public String prompt() {
		return this.prompt;
	}

	/** 生成数量，可能为 null。 */
	public Integer n() {
		return this.n;
	}

	/** 图像尺寸，可能为 null。 */
	public String size() {
		return this.size;
	}

	/** 质量，可能为 null。 */
	public String quality() {
		return this.quality;
	}

	/** 风格，可能为 null。 */
	public String style() {
		return this.style;
	}

	/** 响应格式，可能为 null。 */
	public String responseFormat() {
		return this.responseFormat;
	}

	/** 用户标识，可能为 null。 */
	public String user() {
		return this.user;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
