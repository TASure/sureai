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
 * 提示词缓存控制标记。
 *
 * <p>附在 {@link TextPart} 上，向平台声明该文本片段可被服务端缓存（如 Anthropic 的
 * ephemeral 缓存断点）。当前仅支持 {@code "ephemeral"} 类型。</p>
 *
 * @param type 缓存控制类型，固定为 "ephemeral"
 * @author sureai
 * @since 0.2.0
 */
public record CacheControl(String type) {

	/**
	 * 短期（ephemeral）缓存控制。
	 *
	 * @return ephemeral 缓存控制标记
	 */
	public static CacheControl ephemeral() {
		return new CacheControl("ephemeral");
	}
}
