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
 * 视频生成请求。
 *
 * <p>不可变，通过 {@link #builder()} 构造；{@code model} 与 {@code prompt} 为必填。
 * 视频生成全平台均为异步任务，{@code duration} / {@code size} / {@code ratio} 等字段
 * 平台不支持时自动忽略。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class VideoRequest {

	private final String model;
	private final String prompt;
	private final Integer duration;
	private final String size;
	private final String ratio;
	private final Integer n;
	private final String firstFrameImageUrl;
	private final String lastFrameImageUrl;
	private final String negativePrompt;
	private final Integer seed;
	private final Boolean withAudio;
	private final String resolution;
	private final Map<String, Object> extra;

	private VideoRequest(Builder b) {
		this.model = b.model;
		this.prompt = b.prompt;
		this.duration = b.duration;
		this.size = b.size;
		this.ratio = b.ratio;
		this.n = b.n;
		this.firstFrameImageUrl = b.firstFrameImageUrl;
		this.lastFrameImageUrl = b.lastFrameImageUrl;
		this.negativePrompt = b.negativePrompt;
		this.seed = b.seed;
		this.withAudio = b.withAudio;
		this.resolution = b.resolution;
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
	public static VideoRequest of(String model, String prompt) {
		return builder().model(model).prompt(prompt).build();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String model;
		private String prompt;
		private Integer duration;
		private String size;
		private String ratio;
		private Integer n;
		private String firstFrameImageUrl;
		private String lastFrameImageUrl;
		private String negativePrompt;
		private Integer seed;
		private Boolean withAudio;
		private String resolution;
		private Map<String, Object> extra;

		private Builder() {
		}

		/** 设置模型名。 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/** 设置提示词。 */
		public Builder prompt(String prompt) {
			this.prompt = prompt;
			return this;
		}

		/** 设置时长（秒）。 */
		public Builder duration(Integer duration) {
			this.duration = duration;
			return this;
		}

		/** 设置分辨率尺寸（如 "1280*720"、"1920x1080"）。 */
		public Builder size(String size) {
			this.size = size;
			return this;
		}

		/** 设置宽高比（如 "16:9"、"9:16"）。 */
		public Builder ratio(String ratio) {
			this.ratio = ratio;
			return this;
		}

		/** 设置生成数量。 */
		public Builder n(Integer n) {
			this.n = n;
			return this;
		}

		/** 设置首帧图片 URL。 */
		public Builder firstFrameImageUrl(String url) {
			this.firstFrameImageUrl = url;
			return this;
		}

		/** 设置尾帧图片 URL。 */
		public Builder lastFrameImageUrl(String url) {
			this.lastFrameImageUrl = url;
			return this;
		}

		/** 设置负面提示词。 */
		public Builder negativePrompt(String negativePrompt) {
			this.negativePrompt = negativePrompt;
			return this;
		}

		/** 设置随机种子。 */
		public Builder seed(Integer seed) {
			this.seed = seed;
			return this;
		}

		/** 设置是否生成同步音频。 */
		public Builder withAudio(Boolean withAudio) {
			this.withAudio = withAudio;
			return this;
		}

		/** 设置分辨率等级（如 "720p"、"1080p"、"4k"）。 */
		public Builder resolution(String resolution) {
			this.resolution = resolution;
			return this;
		}

		/** 追加透传字段（平台特定参数）。 */
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
		public VideoRequest build() {
			Assert.notBlank(this.model, "model must not be blank");
			Assert.notBlank(this.prompt, "prompt must not be blank");
			return new VideoRequest(this);
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

	/** 时长（秒），可能为 null。 */
	public Integer duration() {
		return this.duration;
	}

	/** 分辨率尺寸，可能为 null。 */
	public String size() {
		return this.size;
	}

	/** 宽高比，可能为 null。 */
	public String ratio() {
		return this.ratio;
	}

	/** 生成数量，可能为 null。 */
	public Integer n() {
		return this.n;
	}

	/** 首帧图片 URL，可能为 null。 */
	public String firstFrameImageUrl() {
		return this.firstFrameImageUrl;
	}

	/** 尾帧图片 URL，可能为 null。 */
	public String lastFrameImageUrl() {
		return this.lastFrameImageUrl;
	}

	/** 负面提示词，可能为 null。 */
	public String negativePrompt() {
		return this.negativePrompt;
	}

	/** 随机种子，可能为 null。 */
	public Integer seed() {
		return this.seed;
	}

	/** 是否生成同步音频，可能为 null。 */
	public Boolean withAudio() {
		return this.withAudio;
	}

	/** 分辨率等级，可能为 null。 */
	public String resolution() {
		return this.resolution;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
