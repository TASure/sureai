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
 * 联网检索来源（Grounding）。
 *
 * <p>当请求开启联网时，模型回复所引用的网页来源。对应 OpenAI annotations.url_citation、
 * Gemini groundingMetadata 等。</p>
 *
 * @param title   来源标题，可能为 null
 * @param url     来源链接，可能为 null
 * @param content 引用片段/摘要内容，可能为 null
 * @author sureai
 * @since 0.2.0
 */
public record GroundingSource(String title, String url, String content) {

	/**
	 * 静态工厂。
	 *
	 * @param title   标题
	 * @param url     链接
	 * @param content 引用片段
	 * @return 来源
	 */
	public static GroundingSource of(String title, String url, String content) {
		return new GroundingSource(title, url, content);
	}
}
