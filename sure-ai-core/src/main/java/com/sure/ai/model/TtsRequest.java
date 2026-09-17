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
 * 语音合成（TTS）请求。
 *
 * <p>不可变，通过 {@link #builder()} 构造；{@code model}、{@code input}、{@code voice} 为必填。
 * 平台不支持的字段（如 pitch / sampleRate）在序列化时自动忽略。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class TtsRequest {

	private final String model;
	private final String input;
	private final String voice;
	private final String responseFormat;
	private final Double speed;
	private final Double volume;
	private final Double pitch;
	private final Integer sampleRate;
	private final String instructions;
	private final Map<String, Object> extra;

	private TtsRequest(Builder b) {
		this.model = b.model;
		this.input = b.input;
		this.voice = b.voice;
		this.responseFormat = b.responseFormat;
		this.speed = b.speed;
		this.volume = b.volume;
		this.pitch = b.pitch;
		this.sampleRate = b.sampleRate;
		this.instructions = b.instructions;
		this.extra = b.extra == null ? Map.of() : Map.copyOf(b.extra);
	}

	/** 创建 Builder。 */
	public static Builder builder() {
		return new Builder();
	}

	/** 便捷构造：模型/文本/音色。 */
	public static TtsRequest of(String model, String input, String voice) {
		return builder().model(model).input(input).voice(voice).build();
	}

	/** Builder。 */
	public static final class Builder {

		private String model;
		private String input;
		private String voice;
		private String responseFormat;
		private Double speed;
		private Double volume;
		private Double pitch;
		private Integer sampleRate;
		private String instructions;
		private Map<String, Object> extra;

		private Builder() {
		}

		/** 设置模型名。 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/** 设置待合成文本。 */
		public Builder input(String input) {
			this.input = input;
			return this;
		}

		/** 设置音色 ID。 */
		public Builder voice(String voice) {
			this.voice = voice;
			return this;
		}

		/** 设置响应音频格式（mp3/wav/pcm/opus/flac/aac）。 */
		public Builder responseFormat(String responseFormat) {
			this.responseFormat = responseFormat;
			return this;
		}

		/** 设置语速（0.25–4.0，默认 1.0）。 */
		public Builder speed(Double speed) {
			this.speed = speed;
			return this;
		}

		/** 设置音量（0–100 或 0–1，视平台而定）。 */
		public Builder volume(Double volume) {
			this.volume = volume;
			return this;
		}

		/** 设置音调（0.5–2.0，默认 1.0）。 */
		public Builder pitch(Double pitch) {
			this.pitch = pitch;
			return this;
		}

		/** 设置采样率（Hz）。 */
		public Builder sampleRate(Integer sampleRate) {
			this.sampleRate = sampleRate;
			return this;
		}

		/** 设置风格指令（部分模型支持，如 gpt-4o-mini-tts）。 */
		public Builder instructions(String instructions) {
			this.instructions = instructions;
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
		public TtsRequest build() {
			Assert.notBlank(this.model, "model must not be blank");
			Assert.notBlank(this.input, "input must not be blank");
			Assert.notBlank(this.voice, "voice must not be blank");
			return new TtsRequest(this);
		}
	}

	/** 模型名。 */
	public String model() {
		return this.model;
	}

	/** 待合成文本。 */
	public String input() {
		return this.input;
	}

	/** 音色 ID。 */
	public String voice() {
		return this.voice;
	}

	/** 响应格式，可能为 null。 */
	public String responseFormat() {
		return this.responseFormat;
	}

	/** 语速，可能为 null。 */
	public Double speed() {
		return this.speed;
	}

	/** 音量，可能为 null。 */
	public Double volume() {
		return this.volume;
	}

	/** 音调，可能为 null。 */
	public Double pitch() {
		return this.pitch;
	}

	/** 采样率，可能为 null。 */
	public Integer sampleRate() {
		return this.sampleRate;
	}

	/** 风格指令，可能为 null。 */
	public String instructions() {
		return this.instructions;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
