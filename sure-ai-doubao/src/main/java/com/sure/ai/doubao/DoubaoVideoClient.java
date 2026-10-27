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

package com.sure.ai.doubao;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.VideoClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;
import com.sure.ai.model.VideoResult;

/**
 * 火山方舟 Seedance 视频生成客户端（异步任务轮询）。
 *
 * <p>方舟视频生成走原生端点（非 OpenAI 兼容路径）：</p>
 * <ul>
 *   <li>提交任务：{@code POST .../api/v3/contents/generations/tasks}，
 *       body 为 {@code {model, content:[{type:text, text:...}], ratio, duration,
 *       resolution, generate_audio, seed}}，返回 {@code {id, status:queued}}</li>
 *   <li>轮询结果：{@code GET .../api/v3/contents/generations/tasks/{id}}，
 *       {@code status} ∈ {queued, running, succeeded, failed, cancelled, expired}；
 *       成功时 {@code content.video_url}</li>
 * </ul>
 *
 * <p>鉴权为标准 {@code Authorization: Bearer {apiKey}}。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class DoubaoVideoClient extends AbstractAiClient implements VideoClient {

	/** 默认 baseUrl（主机根，路径内含 /api/v3）。 */
	public static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com";

	/** 提交任务路径。 */
	public static final String SUBMIT_PATH = "/api/v3/contents/generations/tasks";

	/** 轮询路径前缀（拼接任务 id）。 */
	public static final String POLL_PATH_PREFIX = "/api/v3/contents/generations/tasks/";

	/** 轮询间隔（毫秒），包级可变便于测试。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** 最大等待时间（毫秒），包级可变便于测试。 */
	static long MAX_WAIT_MS = 120000L;

	/**
	 * 构造客户端；baseUrl 为空时使用火山方舟默认主机。
	 *
	 * @param config 配置
	 */
	public DoubaoVideoClient(AiConfig config) {
		super(withDefaultBaseUrl(config));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "doubao-video"
	 */
	public String name() {
		return "doubao-video";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
	}

	@Override
	public VideoResponse generate(VideoRequest request) {
		JsonObject body = buildBody(request);
		PostResult submit = doPostRaw(SUBMIT_PATH, body);
		String taskId = submit.json().optString("id", null);
		if (taskId == null || taskId.isBlank()) {
			throw new AiException("doubao video task id not found in response: " + submit.rawBody());
		}
		long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
		String lastRaw = submit.rawBody();
		while (System.currentTimeMillis() < deadline) {
			sleepQuietly();
			PostResult poll = doGetRaw(POLL_PATH_PREFIX + encodePathSegment(taskId));
			lastRaw = poll.rawBody();
			String status = poll.json().optString("status", "");
			if ("succeeded".equals(status)) {
				return parseSuccess(poll.json(), poll.rawBody());
			}
			if ("failed".equals(status) || "cancelled".equals(status) || "expired".equals(status)) {
				throw new AiException("doubao video generation " + status + ": " + poll.rawBody());
			}
			// queued / running 继续轮询
		}
		throw new AiTimeoutException("doubao video generation timed out after "
			+ MAX_WAIT_MS + "ms, last: " + lastRaw);
	}

	/** 构造视频生成请求体。 */
	private JsonObject buildBody(VideoRequest req) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		JsonArray content = Json.array();
		JsonObject textPart = Json.object();
		textPart.put("type", "text");
		textPart.put("text", req.prompt());
		content.add(textPart);
		body.put("content", content);
		if (req.ratio() != null) {
			body.put("ratio", req.ratio());
		}
		if (req.duration() != null) {
			body.put("duration", req.duration());
		}
		if (req.resolution() != null) {
			body.put("resolution", req.resolution());
		}
		if (req.withAudio() != null) {
			body.put("generate_audio", req.withAudio());
		}
		if (req.seed() != null) {
			body.put("seed", req.seed());
		}
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	/** 解析成功响应：{status:succeeded, content:{video_url, last_frame_url}}。 */
	private static VideoResponse parseSuccess(JsonObject resp, String rawJson) {
		JsonObject content = resp.has("content") ? resp.getJsonObject("content") : null;
		String url = content == null ? null : content.optString("video_url", null);
		String cover = content == null ? null : content.optString("last_frame_url", null);
		List<VideoResult> results = List.of(VideoResult.of(url, cover, null, "succeeded", null));
		return VideoResponse.of(0L, results, rawJson);
	}

	/** 轮询间隔等待，可中断。 */
	private static void sleepQuietly() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("doubao video polling interrupted", ex);
		}
	}

	/** baseUrl 为空时补默认主机，其余配置原样保留。 */
	private static AiConfig withDefaultBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(config.apiKey())
			.baseUrl(DEFAULT_BASE_URL)
			.timeout(config.timeout())
			.connectTimeout(config.connectTimeout())
			.maxRetries(config.maxRetries());
		if (config.proxy() != null && !config.proxy().isBlank()) {
			b.proxy(config.proxy());
		}
		for (Map.Entry<String, String> e : config.extraHeaders().entrySet()) {
			b.extraHeader(e.getKey(), e.getValue());
		}
		return b.build();
	}
}
