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
 * 单张图像生成结果。
 *
 * <p>平台返回的图像可能以 URL 或 Base64 编码形式提供，两者至少其一非空。</p>
 *
 * @param url           图像访问 URL（可能为 null）
 * @param b64Json       Base64 编码的图像数据（可能为 null）
 * @param revisedPrompt 平台修订后的提示词（可能为 null，如 DALL·E 3 的 revised_prompt）
 * @author sureai
 * @since 0.2.0
 */
public record ImageResult(String url, String b64Json, String revisedPrompt) {

	/**
	 * 静态工厂。
	 *
	 * @param url           图像 URL
	 * @param b64Json       Base64 图像数据
	 * @param revisedPrompt 修订后提示词
	 * @return 结果
	 */
	public static ImageResult of(String url, String b64Json, String revisedPrompt) {
		return new ImageResult(url, b64Json, revisedPrompt);
	}

	/**
	 * 仅含 URL 的工厂。
	 *
	 * @param url 图像 URL
	 * @return 结果
	 */
	public static ImageResult ofUrl(String url) {
		return new ImageResult(url, null, null);
	}

	/**
	 * 仅含 Base64 数据的工厂。
	 *
	 * @param b64Json Base64 图像数据
	 * @return 结果
	 */
	public static ImageResult ofB64(String b64Json) {
		return new ImageResult(null, b64Json, null);
	}
}
