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

package com.sure.ai.azure;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.VideoClient;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;
import com.sure.ai.model.VideoResult;

/**
 * Azure OpenAI Sora 2 视频生成客户端（预览版，异步任务轮询）。
 *
 * <p>鉴权与 {@link AzureClient} 一致：使用 {@code api-key: <apiKey>} 头（非 Bearer）。
 * 协议为异步任务模式：</p>
 * <ul>
 *   <li>提交：{@code POST /openai/v1/video/generations/jobs?api-version=preview}，
 *       body 为 {@code prompt/model/width/height/n_seconds/n_variants}，返回 {@code {id, status}}。</li>
 *   <li>轮询：{@code GET /openai/v1/video/generations/jobs/{id}?api-version=preview}；
 *       状态 queued/preprocessing/running/processing 继续轮询，succeeded 取结果，
 *       failed/cancelled 抛异常。</li>
 *   <li>成功响应 {@code generations:[{id, url?}]}；若未直接给 url，则拼接下载端点
 *       {@code /openai/v1/video/generations/{generation_id}/content/video?api-version=preview}
 *       作为结果 URL（调用方自行下载）。</li>
 * </ul>
 *
 * <p>默认轮询间隔 2s、超时 120s（静态字段，测试可调）。baseUrl 缺省时从
 * {@code extraHeaders("resource")} 或环境变量 {@code SURE_AI_AZURE_RESOURCE} 推导为
 * {@code https://{resource}.openai.azure.com}；api-version 缺省 {@code preview}，
 * 可经 {@code extraHeaders("api-version", ...)} 覆盖。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class AzureVideoClient extends AbstractAiClient implements VideoClient {

	/** 默认 api-version（Sora 2 预览）。 */
	public static final String DEFAULT_API_VERSION = "preview";

	/** 环境变量名：resource 名。 */
	public static final String ENV_RESOURCE = "SURE_AI_AZURE_RESOURCE";

	/** 默认轮询间隔（毫秒）。测试可调整。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** 默认最大等待时间（毫秒）。测试可调整。 */
	static long MAX_WAIT_MS = 120000L;

	/** api-version。 */
	private final String apiVersion;

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（resource/api-version 经 extraHeaders 传入）
	 */
	public AzureVideoClient(AiConfig config) {
		super(normalizeBaseUrl(config));
		this.apiVersion = config.extraHeaders().getOrDefault("api-version", DEFAULT_API_VERSION);
	}

	/**
	 * 客户端名称。
	 *
	 * @return "azure-video"
	 */
	public String name() {
		return "azure-video";
	}

	/** api-version。 */
	public String apiVersion() {
		return this.apiVersion;
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("api-key", cfg.apiKey());
	}

	@Override
	public VideoResponse generate(VideoRequest request) {
		int[] wh = parseSize(request.size());
		JsonObject body = Json.object();
		body.put("prompt", request.prompt());
		body.put("model", request.model());
		body.put("width", wh[0]);
		body.put("height", wh[1]);
		body.put("n_seconds", request.duration() != null ? request.duration() : 5);
		body.put("n_variants", request.n() != null ? request.n() : 1);
		PostResult submit = doPostRaw(submitPath(), body);
		String taskId = submit.json().optString("id", null);
		if (taskId == null || taskId.isBlank()) {
			throw new AiException("azure video task id not found: " + submit.rawBody());
		}
		return pollUntilDone(taskId);
	}

	/** 提交任务路径。 */
	private String submitPath() {
		return "/openai/v1/video/generations/jobs?api-version=" + this.apiVersion;
	}

	/** 轮询任务状态直到终态。 */
	private VideoResponse pollUntilDone(String taskId) {
		String pollPath = "/openai/v1/video/generations/jobs/" + taskId
			+ "?api-version=" + this.apiVersion;
		long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
		String lastRaw = "";
		while (System.currentTimeMillis() < deadline) {
			sleepQuietly();
			PostResult poll = doGetRaw(pollPath);
			JsonObject resp = poll.json();
			lastRaw = poll.rawBody();
			String status = resp.optString("status", "");
			if ("succeeded".equals(status)) {
				return parseResult(resp, poll.rawBody());
			}
			if ("failed".equals(status) || "cancelled".equals(status)) {
				throw new AiApiException(200, null,
					"azure video task " + status, poll.rawBody());
			}
			// queued/preprocessing/running/processing 继续轮询
		}
		throw new AiTimeoutException("azure video task timed out after " + MAX_WAIT_MS
			+ " ms, last: " + lastRaw);
	}

	/** 解析成功响应：generations:[{id, url?}]，无 url 时拼接下载端点。 */
	private VideoResponse parseResult(JsonObject resp, String raw) {
		long created = resp.optLong("created_at", 0L);
		List<VideoResult> results = new ArrayList<>();
		JsonArray gens = resp.has("generations") ? resp.getJsonArray("generations") : null;
		if (gens != null) {
			for (int i = 0; i < gens.size(); i++) {
				JsonObject g = gens.getJsonObject(i);
				String url = g.optString("url", null);
				if (url == null) {
					String genId = g.optString("id", null);
					if (genId != null) {
						url = downloadUrl(genId);
					}
				}
				results.add(VideoResult.of(url, g.optString("cover_image_url", null),
					null, "succeeded", g.optString("revised_prompt", null)));
			}
		}
		return VideoResponse.of(created, results, raw);
	}

	/** 拼接视频内容下载绝对 URL。 */
	private String downloadUrl(String generationId) {
		String base = this.config.baseUrl();
		String path = "/openai/v1/video/generations/" + generationId
			+ "/content/video?api-version=" + this.apiVersion;
		if (base.endsWith("/")) {
			return base + path.substring(1);
		}
		return base + path;
	}

	/** 解析尺寸为 [width, height]，支持 "1280x720" / "1280*720"，缺省 1280x720。 */
	private static int[] parseSize(String size) {
		int w = 1280;
		int h = 720;
		if (size != null && !size.isBlank()) {
			String[] parts = size.trim().toLowerCase().split("[x*×]");
			if (parts.length == 2) {
				try {
					w = Integer.parseInt(parts[0].trim());
					h = Integer.parseInt(parts[1].trim());
				} catch (NumberFormatException ignored) {
					// 解析失败回退默认
				}
			}
		}
		return new int[] { w, h };
	}

	/** 轮询间隔等待，可中断。 */
	private void sleepQuietly() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("azure video polling interrupted", ex);
		}
	}

	/** baseUrl 为 null 时，用 resource 推导默认地址重建配置。 */
	static AiConfig normalizeBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		String resource = config.extraHeaders().get("resource");
		if (resource == null || resource.isBlank()) {
			resource = System.getenv(ENV_RESOURCE);
		}
		if (resource == null || resource.isBlank()) {
			return config;
		}
		return rebuild(config, "https://" + resource + ".openai.azure.com");
	}

	/** 用新 baseUrl 替换配置（其余全部字段通过 {@link AiConfig#withBaseUrl} 原样保留）。 */
	private static AiConfig rebuild(AiConfig config, String baseUrl) {
		return config.withBaseUrl(baseUrl);
	}
}
