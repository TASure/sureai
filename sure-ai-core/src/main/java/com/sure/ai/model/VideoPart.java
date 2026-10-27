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
 * 视频消息片段（视频理解输入），支持 URL 引用或 base64 内联两种形式。
 *
 * <p>各平台把同一视频片段翻译成自家协议：</p>
 * <ul>
 *   <li>OpenAI 兼容（通义千问 Qwen-VL）：{@code {"type":"video_url","video_url":{"url":resolvedUrl()}}}；</li>
 *   <li>Google Gemini：URL/文件引用序列化为 {@code fileData {"fileUri":...,"mimeType":...}}，
 *       base64 内联序列化为 {@code inlineData {"mimeType":...,"data":...}}。</li>
 * </ul>
 *
 * <p>注意：Anthropic Messages API 仅支持图像理解，不支持视频输入，故本片段在 Anthropic
 * 客户端不会被序列化。</p>
 *
 * @param url       视频 URL（或 Gemini Files API 返回的 fileUri），base64 形式时为 null
 * @param base64    base64 数据，URL 形式时为 null
 * @param mimeType  MIME 类型（如 video/mp4），URL 形式时可为 null
 * @author sureai
 * @since 2.4.0
 */
public record VideoPart(String url, String base64, String mimeType) implements MessagePart {

	/**
	 * 按 URL / 文件引用构造。
	 *
	 * @param url 视频 URL 或 Gemini fileUri
	 * @return 视频片段
	 */
	public static VideoPart ofUrl(String url) {
		return new VideoPart(url, null, null);
	}

	/**
	 * 按 base64 数据构造。
	 *
	 * @param base64    base64 编码视频数据
	 * @param mimeType  MIME 类型（如 video/mp4）
	 * @return 视频片段
	 */
	public static VideoPart ofBase64(String base64, String mimeType) {
		return new VideoPart(null, base64, mimeType);
	}

	@Override
	public String type() {
		return "video_url";
	}

	/**
	 * 返回可序列化为 OpenAI 兼容 {@code video_url.url} 的地址。
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
