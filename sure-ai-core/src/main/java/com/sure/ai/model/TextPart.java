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
 * 文本消息片段。
 *
 * @param text         文本内容
 * @param cacheControl 提示词缓存控制标记，不缓存时为 null
 * @author sureai
 * @since 0.1.0
 */
public record TextPart(String text, CacheControl cacheControl) implements MessagePart {

	/**
	 * 紧凑构造器：规范化缓存控制。
	 *
	 * @param text         文本内容
	 * @param cacheControl 缓存控制标记
	 */
	public TextPart {
		if (cacheControl != null && cacheControl.type() == null) {
			cacheControl = null;
		}
	}

	/**
	 * 静态工厂（不带缓存控制）。
	 *
	 * @param text 文本
	 * @return 文本片段
	 */
	public static TextPart of(String text) {
		return new TextPart(text, null);
	}

	/**
	 * 带缓存控制的静态工厂。
	 *
	 * @param text         文本
	 * @param cacheControl 缓存控制标记
	 * @return 文本片段
	 */
	public static TextPart ofWithCache(String text, CacheControl cacheControl) {
		return new TextPart(text, cacheControl);
	}

	@Override
	public String type() {
		return "text";
	}
}
