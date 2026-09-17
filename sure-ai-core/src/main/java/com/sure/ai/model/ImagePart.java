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
 * 图片消息片段，支持 URL 或 base64 内联两种形式。
 *
 * <p>序列化时 image_url 字段为 {@code {"url": "..."}}，base64 形式输出为
 * {@code data:<mime>;base64,<data>}。</p>
 *
 * @param url      图片 URL，base64 形式时为 null
 * @param base64   base64 数据，URL 形式时为 null
 * @param mimeType MIME 类型，URL 形式时为 null
 * @author sureai
 * @since 0.1.0
 */
public record ImagePart(String url, String base64, String mimeType) implements MessagePart {

	/**
	 * 按 URL 构造。
	 *
	 * @param url 图片 URL
	 * @return 图片片段
	 */
	public static ImagePart ofUrl(String url) {
		return new ImagePart(url, null, null);
	}

	/**
	 * 按 base64 数据构造。
	 *
	 * @param base64    base64 编码图片数据
	 * @param mimeType  MIME 类型（如 image/png）
	 * @return 图片片段
	 */
	public static ImagePart ofBase64(String base64, String mimeType) {
		return new ImagePart(null, base64, mimeType);
	}

	@Override
	public String type() {
		return "image_url";
	}

	/**
	 * 返回可序列化为 image_url.url 的地址。
	 *
	 * @return URL 或 data URI
	 */
	public String resolvedUrl() {
		if (this.url != null) {
			return this.url;
		}
		return "data:" + this.mimeType + ";base64," + this.base64;
	}
}
