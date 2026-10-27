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

package com.sure.ai.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.ImageResult;

/**
 * Image 能力域策略：{@code /images/generations} 请求构建与
 * {@code {created, data:[{url, b64_json, revised_prompt}]}} 响应解析。
 *
 * @author sureai
 * @since 1.4.0
 */
final class ImageCompatStrategy {

	/** 持有外层客户端引用，用于传输与 imagesPath 字段。 */
	private final OpenAiCompatClient client;

	/**
	 * @param client 外层 OpenAI 兼容客户端
	 */
	ImageCompatStrategy(OpenAiCompatClient client) {
		this.client = client;
	}

	/** 图像生成入口。 */
	ImageResponse generate(ImageRequest request) {
		JsonObject body = buildImageBody(request);
		var result = this.client.transportPostRaw(this.client.imagesPath, body);
		return parseImageResponse(result.json(), result.rawBody());
	}

	/** 构造图像生成请求体。 */
	private JsonObject buildImageBody(ImageRequest req) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		body.put("prompt", req.prompt());
		CompatJson.putIfNotNull(body, "n", req.n());
		CompatJson.putIfNotNull(body, "size", req.size());
		CompatJson.putIfNotNull(body, "quality", req.quality());
		CompatJson.putIfNotNull(body, "style", req.style());
		CompatJson.putIfNotNull(body, "response_format", req.responseFormat());
		CompatJson.putIfNotNull(body, "user", req.user());
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	/** 解析图像生成响应：{created, data:[{url, b64_json, revised_prompt}]}。 */
	private ImageResponse parseImageResponse(JsonObject resp, String rawJson) {
		long created = resp.optLong("created", 0L);
		List<ImageResult> results = new ArrayList<>();
		JsonArray data = resp.has("data") ? resp.getJsonArray("data") : null;
		if (data != null) {
			for (int i = 0; i < data.size(); i++) {
				JsonObject d = data.getJsonObject(i);
				results.add(ImageResult.of(
					d.optString("url", null),
					d.optString("b64_json", null),
					d.optString("revised_prompt", null)));
			}
		}
		return ImageResponse.of(created, results, rawJson);
	}
}
