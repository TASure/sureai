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

import java.util.List;

/**
 * 语音识别（STT）响应。
 *
 * @param text      转写文本
 * @param language  检测到的语言（可能为 null）
 * @param duration  音频时长（秒，可能为 null）
 * @param segments  段级时间戳列表（verbose_json 模式下可能非空）
 * @param words     词级时间戳列表（verbose_json + word 粒度下可能非空）
 * @param rawJson   原始响应 JSON
 * @author sureai
 * @since 0.2.0
 */
public record SttResponse(String text, String language, Double duration,
		List<Segment> segments, List<Word> words, String rawJson) {

	/**
	 * 全参构造器（防御性拷贝）。
	 */
	public SttResponse {
		segments = segments == null ? List.of() : List.copyOf(segments);
		words = words == null ? List.of() : List.copyOf(words);
	}

	/**
	 * 静态工厂：仅文本。
	 *
	 * @param text 转写文本
	 * @return 响应
	 */
	public static SttResponse ofText(String text) {
		return new SttResponse(text, null, null, List.of(), List.of(), null);
	}

	/**
	 * 静态工厂：完整字段。
	 *
	 * @param text     转写文本
	 * @param language 语言
	 * @param duration 时长
	 * @param segments 段列表
	 * @param words    词列表
	 * @param rawJson  原始 JSON
	 * @return 响应
	 */
	public static SttResponse of(String text, String language, Double duration,
			List<Segment> segments, List<Word> words, String rawJson) {
		return new SttResponse(text, language, duration, segments, words, rawJson);
	}
}
