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
 * 语音识别（STT/ASR/转录）请求。
 *
 * <p>不可变，通过 {@link #builder()} 构造；{@code model} 与 {@code audioData} 为必填。
 * 音频上传格式因平台而异（multipart/form-data / JSON base64 / 二进制 body / URL），
 * 由各平台客户端内部适配。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class SttRequest {

	private final String model;
	private final byte[] audioData;
	private final String fileName;
	private final String contentType;
	private final String language;
	private final String responseFormat;
	private final Double temperature;
	private final String prompt;
	private final Map<String, Object> extra;

	private SttRequest(Builder b) {
		this.model = b.model;
		this.audioData = b.audioData == null ? null : b.audioData.clone();
		this.fileName = b.fileName;
		this.contentType = b.contentType;
		this.language = b.language;
		this.responseFormat = b.responseFormat;
		this.temperature = b.temperature;
		this.prompt = b.prompt;
		this.extra = b.extra == null ? Map.of() : Map.copyOf(b.extra);
	}

	/** 创建 Builder。 */
	public static Builder builder() {
		return new Builder();
	}

	/** 便捷构造：模型 + 音频数据。 */
	public static SttRequest of(String model, byte[] audioData) {
		return builder().model(model).audioData(audioData).build();
	}

	/** Builder。 */
	public static final class Builder {

		private String model;
		private byte[] audioData;
		private String fileName;
		private String contentType;
		private String language;
		private String responseFormat;
		private Double temperature;
		private String prompt;
		private Map<String, Object> extra;

		private Builder() {
		}

		/** 设置模型名。 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/** 设置音频二进制数据（防御性拷贝）。 */
		public Builder audioData(byte[] audioData) {
			this.audioData = audioData == null ? null : audioData.clone();
			return this;
		}

		/** 设置文件名（multipart 上传时使用，如 "audio.mp3"）。 */
		public Builder fileName(String fileName) {
			this.fileName = fileName;
			return this;
		}

		/** 设置音频 MIME 类型（如 "audio/mpeg"、"audio/wav"）。 */
		public Builder contentType(String contentType) {
			this.contentType = contentType;
			return this;
		}

		/** 设置语言代码（ISO-639-1，如 "en"、"zh"），不传则自动检测。 */
		public Builder language(String language) {
			this.language = language;
			return this;
		}

		/** 设置响应格式（json/text/srt/vtt/verbose_json）。 */
		public Builder responseFormat(String responseFormat) {
			this.responseFormat = responseFormat;
			return this;
		}

		/** 设置采样温度（0–1）。 */
		public Builder temperature(Double temperature) {
			this.temperature = temperature;
			return this;
		}

		/** 设置前缀提示词（校正专有名词/风格）。 */
		public Builder prompt(String prompt) {
			this.prompt = prompt;
			return this;
		}

		/** 追加透传字段。 */
		public Builder extra(String key, Object value) {
			if (this.extra == null) {
				this.extra = new LinkedHashMap<>();
			}
			this.extra.put(key, value);
			return this;
		}

		/** 构建请求，校验必填字段。 */
		public SttRequest build() {
			Assert.notBlank(this.model, "model must not be blank");
			Assert.notNull(this.audioData, "audioData must not be null");
			return new SttRequest(this);
		}
	}

	/** 模型名。 */
	public String model() {
		return this.model;
	}

	/** 音频二进制数据（防御性拷贝）。 */
	public byte[] audioData() {
		return this.audioData == null ? null : this.audioData.clone();
	}

	/** 文件名，可能为 null。 */
	public String fileName() {
		return this.fileName;
	}

	/** 音频 MIME 类型，可能为 null。 */
	public String contentType() {
		return this.contentType;
	}

	/** 语言代码，可能为 null。 */
	public String language() {
		return this.language;
	}

	/** 响应格式，可能为 null。 */
	public String responseFormat() {
		return this.responseFormat;
	}

	/** 温度，可能为 null。 */
	public Double temperature() {
		return this.temperature;
	}

	/** 前缀提示词，可能为 null。 */
	public String prompt() {
		return this.prompt;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
