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
 * STT 段级时间戳。
 *
 * @param id     段序号
 * @param start  起始时间（秒）
 * @param end    结束时间（秒）
 * @param text   段文本
 * @param words  词级时间戳列表（verbose_json 模式下可能非空）
 * @author sureai
 * @since 0.2.0
 */
public record Segment(int id, double start, double end, String text, List<Word> words) {

	/** 规范构造器：防御性拷贝词级列表。 */
	public Segment {
		words = words == null ? List.of() : List.copyOf(words);
	}

	/** 静态工厂（无词级时间戳）。 */
	public static Segment of(int id, double start, double end, String text) {
		return new Segment(id, start, end, text, List.of());
	}

	/** 静态工厂（含词级时间戳）。 */
	public static Segment of(int id, double start, double end, String text, List<Word> words) {
		return new Segment(id, start, end, text, words == null ? List.of() : List.copyOf(words));
	}
}
