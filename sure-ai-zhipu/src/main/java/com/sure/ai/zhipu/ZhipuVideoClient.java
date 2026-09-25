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

package com.sure.ai.zhipu;

import java.net.http.HttpRequest;
import java.util.ArrayList;
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
 * 智谱 CogVideoX 视频生成客户端（异步任务轮询）。
 *
 * <p>协议与 OpenAI 兼容客户端不同，需独立实现：</p>
 * <ul>
 *   <li>提交任务：{@code POST https://open.bigmodel.cn/api/paas/v4/videos/generations}，
 *       返回 {@code {id, task_status: PROCESSING}}</li>
 *   <li>轮询结果：{@code GET .../api/paas/v4/async-result/{id}}，
 *       {@code task_status} ∈ {PROCESSING, SUCCESS, FAIL}；成功时
 *       {@code video_result:[{url, cover_image_url}]}</li>
 * </ul>
 *
 * <p>鉴权与 {@link ZhipuClient} 一致：{@code id.secret} 现场签发 HS256 JWT 作 Bearer 并缓存。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class ZhipuVideoClient extends AbstractAiClient implements VideoClient {

	/** 默认 baseUrl（主机根，路径内含 /api/paas/v4）。 */
	public static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn";

	/** 提交任务路径。 */
	public static final String SUBMIT_PATH = "/api/paas/v4/videos/generations";

	/** 轮询路径前缀（拼接任务 id）。 */
	public static final String POLL_PATH_PREFIX = "/api/paas/v4/async-result/";

	/** JWT 提前刷新的安全余量（毫秒）。 */
	private static final long REFRESH_SKEW_MS = 60_000L;

	/** 轮询间隔（毫秒），包级可变便于测试。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** 最大等待时间（毫秒），包级可变便于测试。 */
	static long MAX_WAIT_MS = 120000L;

	/** 已缓存的 Bearer token（含 "Bearer " 前缀）。 */
	private volatile String cachedToken;

	/** token 过期时间戳（毫秒）。 */
	private volatile long expireAt;

	/**
	 * 构造客户端；baseUrl 为空时使用智谱默认主机。
	 *
	 * @param config 配置（apiKey 必须是 {@code id.secret} 格式）
	 */
	public ZhipuVideoClient(AiConfig config) {
		super(withDefaultBaseUrl(config));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "zhipu-video"
	 */
	public String name() {
		return "zhipu-video";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", resolveToken(cfg.apiKey()));
	}

	@Override
	public VideoResponse generate(VideoRequest request) {
		JsonObject body = buildBody(request);
		PostResult submit = doPostRaw(SUBMIT_PATH, body);
		String taskId = submit.json().optString("id", null);
		if (taskId == null || taskId.isBlank()) {
			throw new AiException("zhipu video task id not found in response: " + submit.rawBody());
		}
		long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
		String lastRaw = submit.rawBody();
		while (System.currentTimeMillis() < deadline) {
			sleepQuietly();
			PostResult poll = doGetRaw(POLL_PATH_PREFIX + taskId);
			lastRaw = poll.rawBody();
			String status = poll.json().optString("task_status", "");
			if ("SUCCESS".equals(status)) {
				return parseSuccess(poll.json(), poll.rawBody());
			}
			if ("FAIL".equals(status)) {
				throw new AiException("zhipu video generation failed: " + poll.rawBody());
			}
			// PROCESSING 继续轮询
		}
		throw new AiTimeoutException("zhipu video generation timed out after "
			+ MAX_WAIT_MS + "ms, last: " + lastRaw);
	}

	/** 构造视频生成请求体。 */
	private JsonObject buildBody(VideoRequest req) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		body.put("prompt", req.prompt());
		if (req.size() != null) {
			body.put("size", req.size());
		}
		if (req.duration() != null) {
			body.put("duration", req.duration());
		}
		if (Boolean.TRUE.equals(req.withAudio())) {
			body.put("with_audio", true);
		}
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	/** 解析成功响应：{created, task_status:SUCCESS, video_result:[{url, cover_image_url}]}。 */
	private static VideoResponse parseSuccess(JsonObject resp, String rawJson) {
		long created = resp.optLong("created", 0L);
		List<VideoResult> results = new ArrayList<>();
		JsonArray vr = resp.has("video_result") ? resp.getJsonArray("video_result") : null;
		if (vr != null) {
			for (int i = 0; i < vr.size(); i++) {
				JsonObject o = vr.getJsonObject(i);
				results.add(VideoResult.of(
					o.optString("url", null),
					o.optString("cover_image_url", null),
					null,
					resp.optString("task_status", "SUCCESS"),
					null));
			}
		}
		return VideoResponse.of(created, results, rawJson);
	}

	/** 轮询间隔等待，可中断。 */
	private static void sleepQuietly() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("zhipu video polling interrupted", ex);
		}
	}

	/** 解析可用的 Bearer token：缓存未过期直接复用，否则双检锁重新签发。 */
	private String resolveToken(String apiKey) {
		String token = this.cachedToken;
		if (token != null && System.currentTimeMillis() < this.expireAt - REFRESH_SKEW_MS) {
			return token;
		}
		synchronized (this) {
			if (this.cachedToken == null
				|| System.currentTimeMillis() >= this.expireAt - REFRESH_SKEW_MS) {
				this.cachedToken = ZhipuJwtGenerator.generate(apiKey);
				this.expireAt = System.currentTimeMillis() + 3_600_000L;
			}
			return this.cachedToken;
		}
	}

	/** baseUrl 为空时补默认主机，其余配置通过 {@link AiConfig#withBaseUrl} 原样保留。 */
	private static AiConfig withDefaultBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(DEFAULT_BASE_URL);
	}
}
