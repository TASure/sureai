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
 * 图像生成响应。
 *
 * @param created  创建时间戳（Unix 秒，平台未提供时为 0）
 * @param data     生成的图像结果列表
 * @param rawJson  原始响应 JSON（便于调试与平台扩展字段提取）
 * @author sureai
 * @since 0.2.0
 */
public record ImageResponse(long created, List<ImageResult> data, String rawJson) {

	/**
	 * 全参构造器（防御性拷贝）。
	 */
	public ImageResponse {
		data = data == null ? List.of() : List.copyOf(data);
	}

	/**
	 * 静态工厂。
	 *
	 * @param created 创建时间戳
	 * @param data    图像结果列表
	 * @param rawJson 原始响应 JSON
	 * @return 响应
	 */
	public static ImageResponse of(long created, List<ImageResult> data, String rawJson) {
		return new ImageResponse(created, data, rawJson);
	}

	/**
	 * 取第一张图像的 URL。
	 *
	 * @return URL，无结果或无 URL 时返回 null
	 */
	public String firstUrl() {
		if (this.data.isEmpty()) {
			return null;
		}
		return this.data.get(0).url();
	}

	/**
	 * 取第一张图像的 Base64 数据。
	 *
	 * @return Base64 数据，无结果或无 b64 时返回 null
	 */
	public String firstB64() {
		if (this.data.isEmpty()) {
			return null;
		}
		return this.data.get(0).b64Json();
	}
}
