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

/**
 * 语音合成（TTS）响应。
 *
 * <p>平台响应形态分两派：OpenAI / 百度 / Azure 直接返回二进制音频流（{@link #audio} 非空）；
 * 阿里云 / 火山引擎返回 JSON 含音频 URL（{@link #url} 非空）。两者至少其一非空。</p>
 *
 * @param audio    二进制音频数据（直接返回时非空）
 * @param format   音频格式（mp3/wav/pcm/opus 等）
 * @param url      音频文件 URL（平台返回 URL 时非空，通常有时效性）
 * @param rawJson  原始响应 JSON（URL 模式下保留，便于调试）
 * @author sureai
 * @since 0.2.0
 */
public record TtsResponse(byte[] audio, String format, String url, String rawJson) {

	/** 规范构造器：防御性拷贝音频数组。 */
	public TtsResponse {
		if (audio != null) {
			audio = audio.clone();
		}
	}

	/**
	 * 静态工厂：二进制音频。
	 *
	 * @param audio  二进制音频数据
	 * @param format 音频格式
	 * @return 响应
	 */
	public static TtsResponse ofAudio(byte[] audio, String format) {
		return new TtsResponse(audio, format, null, null);
	}

	/**
	 * 静态工厂：URL 音频。
	 *
	 * @param url     音频 URL
	 * @param format  音频格式
	 * @param rawJson 原始响应 JSON
	 * @return 响应
	 */
	public static TtsResponse ofUrl(String url, String format, String rawJson) {
		return new TtsResponse(null, format, url, rawJson);
	}

	/**
	 * 取音频字节长度。
	 *
	 * @return 字节数，无二进制数据时返回 0
	 */
	public int audioLength() {
		return this.audio == null ? 0 : this.audio.length;
	}

	/** 取音频数据（防御性拷贝）。 */
	@Override
	public byte[] audio() {
		return this.audio == null ? null : this.audio.clone();
	}
}
