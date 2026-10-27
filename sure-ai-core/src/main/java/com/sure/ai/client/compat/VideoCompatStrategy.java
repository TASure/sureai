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

import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;
import com.sure.ai.model.VideoResult;

/**
 * Video 能力域策略：{@code /videos} 异步任务提交 + 状态轮询 + 结果解析。
 *
 * <p>轮询间隔与最大等待时间取自外层可被子类覆写的
 * {@code videoPollIntervalMs}/{@code videoMaxWaitMs} 字段。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
final class VideoCompatStrategy {

	/** 持有外层客户端引用，用于传输、轮询参数与路径编码。 */
	private final OpenAiCompatClient client;

	/**
	 * @param client 外层 OpenAI 兼容客户端
	 */
	VideoCompatStrategy(OpenAiCompatClient client) {
		this.client = client;
	}

	/** 视频生成入口：提交任务后轮询至 completed/failed/超时。 */
	VideoResponse generate(VideoRequest request) {
		JsonObject body = buildVideoBody(request);
		var submit = this.client.transportPostRaw(this.client.videosPath, body);
		String taskId = submit.json().optString("id", null);
		if (taskId == null || taskId.isBlank()) {
			throw new AiException("video task id not found in response: " + submit.rawBody());
		}
		long deadline = System.currentTimeMillis() + this.client.videoMaxWaitMs;
		String lastRaw = submit.rawBody();
		while (System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(this.client.videoPollIntervalMs);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AiException("video polling interrupted", ex);
			}
			var poll = this.client.transportGetRaw(
				this.client.videosPath + "/" + this.client.encodeSegment(taskId));
			lastRaw = poll.rawBody();
			String status = poll.json().optString("status", "");
			if ("completed".equals(status)) {
				return parseVideoResponse(poll.json(), poll.rawBody());
			}
			if ("failed".equals(status)) {
				String reason = poll.json().optString("failure_reason",
					poll.json().optString("error", "video generation failed"));
				throw new AiException("video generation failed: " + reason);
			}
		}
		throw new AiTimeoutException("video generation timed out after "
			+ this.client.videoMaxWaitMs + "ms, last: " + lastRaw);
	}

	/** 构造视频生成请求体。 */
	private JsonObject buildVideoBody(VideoRequest req) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		body.put("prompt", req.prompt());
		CompatJson.putIfNotNull(body, "size", req.size());
		CompatJson.putIfNotNull(body, "seconds", req.duration());
		CompatJson.putIfNotNull(body, "n", req.n());
		CompatJson.putIfNotNull(body, "negative_prompt", req.negativePrompt());
		CompatJson.putIfNotNull(body, "seed", req.seed());
		CompatJson.putIfNotNull(body, "resolution", req.resolution());
		CompatJson.putIfNotNull(body, "ratio", req.ratio());
		if (Boolean.TRUE.equals(req.withAudio())) {
			body.put("with_audio", true);
		}
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	/** 解析视频生成响应：{created_at, status, data:[{url}] 或 video:{url}}。 */
	private VideoResponse parseVideoResponse(JsonObject resp, String rawJson) {
		long created = resp.optLong("created_at", resp.optLong("created", 0L));
		List<VideoResult> results = new ArrayList<>();
		JsonArray data = resp.has("data") ? resp.getJsonArray("data") : null;
		if (data != null && !data.isEmpty()) {
			for (int i = 0; i < data.size(); i++) {
				JsonObject d = data.getJsonObject(i);
				results.add(VideoResult.of(
					d.optString("url", null),
					d.optString("cover_image_url", null),
					d.optString("b64_json", null),
					resp.optString("status", null),
					d.optString("revised_prompt", null)));
			}
		} else if (resp.has("video")) {
			JsonObject v = resp.getJsonObject("video");
			results.add(VideoResult.of(
				v.optString("url", null),
				v.optString("cover_image_url", null),
				null,
				resp.optString("status", null),
				null));
		} else {
			String url = resp.optString("url", null);
			if (url != null) {
				results.add(VideoResult.ofUrl(url));
			}
		}
		return VideoResponse.of(created, results, rawJson);
	}
}
