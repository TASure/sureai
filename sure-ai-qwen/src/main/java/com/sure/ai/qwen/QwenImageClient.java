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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.ImageClient;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.ImageResult;

/**
 * 阿里云百炼通义万相（DashScope wanx）文生图客户端。
 *
 * <p>采用「提交异步任务 → 轮询任务状态」模式，对外同步返回：</p>
 * <ul>
 *   <li>提交任务：{@code POST /api/v1/services/aigc/text2image/image-synthesis}，
 *       需额外请求头 {@code X-DashScope-Async: enable}（提交时手动构造请求，轮询 GET 不需要该头）。</li>
 *   <li>轮询结果：{@code GET /api/v1/tasks/{task_id}}，{@code task_status} 枚举
 *       PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN。</li>
 * </ul>
 *
 * <p>鉴权为 {@code Authorization: Bearer <apiKey>}；默认 baseUrl 为 DashScope 原生 API 地址
 * {@code https://dashscope.aliyuncs.com}（非 compatible-mode 地址）。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenImageClient extends AbstractAiClient implements ImageClient {

	/** DashScope 原生 API 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

	/** 提交异步任务路径。 */
	private static final String SUBMIT_PATH =
		"/api/v1/services/aigc/text2image/image-synthesis";

	/** 默认轮询间隔（毫秒）。测试可调整。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** 默认最大等待时间（毫秒）。测试可调整。 */
	static long MAX_WAIT_MS = 120000L;

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public QwenImageClient(AiConfig config) {
		super(applyDefaultBaseUrl(config, DEFAULT_BASE_URL));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "qwen-image"
	 */
	public String name() {
		return "qwen-image";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
	}

	@Override
	public ImageResponse generate(ImageRequest request) {
		JsonObject submit = submitTask(buildBody(request));
		JsonObject output = submit.getJsonObject("output");
		String taskId = output.getString("task_id");
		return waitForResult(taskId);
	}

	/** 构造文生图请求体。 */
	private JsonObject buildBody(ImageRequest request) {
		JsonObject input = Json.object();
		input.put("prompt", request.prompt());
		JsonObject parameters = Json.object();
		parameters.put("size", dashScopeSize(request.size()));
		parameters.put("n", request.n() != null ? request.n() : 1);
		parameters.put("style", request.style() != null ? request.style() : "auto");
		JsonObject body = Json.object();
		body.put("model", request.model());
		body.put("input", input);
		body.put("parameters", parameters);
		return body;
	}

	/**
	 * 提交异步任务：手动构造请求以附加 {@code X-DashScope-Async: enable} 头。
	 *
	 * <p>AbstractAiClient 的 doPost 不支持 per-request 额外头，而该头不应出现在轮询 GET 上，
	 * 故此处直接用 httpClient 发送。</p>
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
			throw new AiTimeoutException("qwen image submit failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("qwen image submit interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			throw mapError(status, resp.body());
		}
		return parseJson(resp.body());
	}

	/** 轮询任务结果，直到成功、失败或超时。 */
	private ImageResponse waitForResult(String taskId) {
		String path = "/api/v1/tasks/" + encodePathSegment(taskId);
		long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
		while (true) {
			PostResult poll = doGetRaw(path);
			JsonObject output = poll.json().getJsonObject("output");
			String status = output.optString("task_status", "UNKNOWN");
			if ("SUCCEEDED".equals(status)) {
				return buildResponse(output, poll.rawBody());
			}
			if ("FAILED".equals(status)) {
				String msg = output.optString("message", "unknown error");
				throw new AiApiException(200, null, "qwen image task failed: " + msg, poll.rawBody());
			}
			if (System.currentTimeMillis() + POLL_INTERVAL_MS > deadline) {
				throw new AiTimeoutException("qwen image task polling timeout after "
					+ MAX_WAIT_MS + " ms");
			}
			sleepQuietly();
		}
	}

	/** 从 output.results 提取图片 URL 列表。 */
	private static ImageResponse buildResponse(JsonObject output, String raw) {
		List<ImageResult> results = new ArrayList<>();
		if (output.has("results")) {
			JsonArray arr = output.getJsonArray("results");
			for (int i = 0; i < arr.size(); i++) {
				String url = arr.getJsonObject(i).optString("url", null);
				if (url != null) {
					results.add(ImageResult.ofUrl(url));
				}
			}
		}
		return ImageResponse.of(0, results, raw);
	}

	/** 尺寸归一化：DashScope 用 {@code 1024*1024} 格式，兼容 {@code x/X} 分隔。 */
	private static String dashScopeSize(String size) {
		if (size == null || size.isBlank()) {
			return "1024*1024";
		}
		return size.trim().replace('x', '*').replace('X', '*');
	}

	/** 轮询间隔等待，可中断。 */
	private static void sleepQuietly() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("qwen image polling interrupted", ex);
		}
	}

	/**
	 * baseUrl 为空时用默认地址替换（其余全部字段通过 {@link AiConfig#withBaseUrl} 原样保留）。
	 *
	 * @param config      原始配置
	 * @param defaultBase 默认 baseUrl
	 * @return 补齐 baseUrl 后的配置
	 */
	static AiConfig applyDefaultBaseUrl(AiConfig config, String defaultBase) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(defaultBase);
	}
}
