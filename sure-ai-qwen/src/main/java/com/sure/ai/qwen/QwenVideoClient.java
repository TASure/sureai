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

package com.sure.ai.qwen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.VideoClient;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;
import com.sure.ai.model.VideoResult;

/**
 * 阿里云百炼通义万相（DashScope Wan）文生视频客户端。
 *
 * <p>采用「提交异步任务 → 轮询任务状态」模式，对外同步返回：</p>
 * <ul>
 *   <li>提交任务：{@code POST /api/v1/services/aigc/video-generation/video-synthesis}，
 *       需额外请求头 {@code X-DashScope-Async: enable}。</li>
 *   <li>轮询结果：{@code GET /api/v1/tasks/{task_id}}，{@code task_status} 枚举
 *       PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED。</li>
 * </ul>
 *
 * <p>鉴权为 {@code Authorization: Bearer <apiKey>}；默认 baseUrl 为 DashScope 原生 API 地址
 * {@code https://dashscope.aliyuncs.com}。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenVideoClient extends AbstractAiClient implements VideoClient {

	/** DashScope 原生 API 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

	/** 提交异步任务路径。 */
	private static final String SUBMIT_PATH =
		"/api/v1/services/aigc/video-generation/video-synthesis";

	/** 任务轮询路径前缀。 */
	private static final String TASK_PATH_PREFIX = "/api/v1/tasks/";

	/** 默认轮询间隔（毫秒）。测试可调整。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** 默认最大等待时间（毫秒）。测试可调整。 */
	static long MAX_WAIT_MS = 120000L;

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public QwenVideoClient(AiConfig config) {
		super(QwenImageClient.applyDefaultBaseUrl(config, DEFAULT_BASE_URL));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "qwen-video"
	 */
	public String name() {
		return "qwen-video";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
	}

	@Override
	public VideoResponse generate(VideoRequest request) {
		JsonObject submit = submitTask(buildBody(request));
		JsonObject output = submit.getJsonObject("output");
		String taskId = output.getString("task_id");
		return waitForResult(taskId);
	}

	/** 构造文生视频请求体。 */
	private JsonObject buildBody(VideoRequest request) {
		JsonObject input = Json.object();
		input.put("prompt", request.prompt());
		if (request.negativePrompt() != null) {
			input.put("negative_prompt", request.negativePrompt());
		}
		JsonObject parameters = Json.object();
		if (request.size() != null && !request.size().isBlank()) {
			parameters.put("size", dashScopeSize(request.size()));
		}
		if (request.duration() != null) {
			parameters.put("duration", request.duration());
		}
		if (request.seed() != null) {
			parameters.put("seed", request.seed());
		}
		JsonObject body = Json.object();
		body.put("model", request.model());
		body.put("input", input);
		body.put("parameters", parameters);
		return body;
	}

	/**
	 * 提交异步任务：手动构造请求以附加 {@code X-DashScope-Async: enable} 头。
	 *
	 * <p>该头不应出现在轮询 GET 上，故此处直接用 httpClient 发送。</p>
	 */
	private JsonObject submitTask(JsonObject body) {
		String url = this.config.baseUrl() + SUBMIT_PATH;
		String payload = Json.stringify(body);
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.header("X-DashScope-Async", "enable")
			.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
		applyAuth(rb, this.config);
		for (Map.Entry<String, String> e : this.config.extraHeaders().entrySet()) {
			rb.header(e.getKey(), e.getValue());
		}
		HttpResponse<String> resp;
		try {
			resp = this.httpClient.send(rb.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiTimeoutException("qwen video submit failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("qwen video submit interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			throw mapError(status, resp.body());
		}
		return parseJson(resp.body());
	}

	/** 轮询任务结果，直到成功、失败或超时。 */
	private VideoResponse waitForResult(String taskId) {
		String path = TASK_PATH_PREFIX + taskId;
		long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
		while (true) {
			PostResult poll = doGetRaw(path);
			JsonObject output = poll.json().getJsonObject("output");
			String status = output.optString("task_status", "UNKNOWN");
			if ("SUCCEEDED".equals(status)) {
				return buildResponse(output, poll.rawBody());
			}
			if ("FAILED".equals(status) || "CANCELED".equals(status)) {
				String msg = output.optString("message", status);
				throw new AiApiException(200, null, "qwen video task " + status + ": " + msg,
					poll.rawBody());
			}
			if (System.currentTimeMillis() + POLL_INTERVAL_MS > deadline) {
				throw new AiTimeoutException("qwen video task polling timeout after "
					+ MAX_WAIT_MS + " ms");
			}
			sleepQuietly();
		}
	}

	/** 从 output.video_url 提取视频结果。 */
	private static VideoResponse buildResponse(JsonObject output, String raw) {
		String videoUrl = output.optString("video_url", null);
		List<VideoResult> results = videoUrl == null
			? List.of()
			: List.of(VideoResult.ofUrl(videoUrl));
		return VideoResponse.of(0L, results, raw);
	}

	/** 尺寸归一化：DashScope 用 {@code 1280*720} 格式，兼容 {@code x/X} 分隔。 */
	private static String dashScopeSize(String size) {
		return size.trim().replace('x', '*').replace('X', '*');
	}

	/** 轮询间隔等待，可中断。 */
	private static void sleepQuietly() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("qwen video polling interrupted", ex);
		}
	}
}
