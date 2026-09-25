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

package com.sure.ai.baidu;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.ImageClient;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.ImageResult;

/**
 * 百度文心一格（ERNIE-ViLG）文生图客户端。
 *
 * <p>鉴权与 {@link BaiduClient} 一致：OAuth access_token 拼在 URL 查询串，按 {@code expires_in}
 * 缓存（提前 60 秒刷新），并发下只刷新一次。任务模式为异步轮询：</p>
 * <ul>
 *   <li>提交任务：{@code POST /rpc/2.0/ernievilg/v1/txt2imgv2?access_token=<token>}，
 *       body 为 {@code prompt/width/height/image_num}，成功时 {@code code=0}。</li>
 *   <li>轮询结果：{@code POST /rpc/2.0/ernievilg/v1/getImgv2?access_token=<token>}，
 *       body 为 {@code {"task_id":"..."}}；{@code data.status} 0=等待 1=运行 2=成功 3=失败。</li>
 * </ul>
 *
 * <p>默认 baseUrl：{@code https://aip.baidubce.com}；secretKey 从
 * {@code extraHeaders("secretKey", ...)} 读取。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class BaiduImageClient extends AbstractAiClient implements ImageClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://aip.baidubce.com";

	/** access_token 提前刷新的安全余量（毫秒）。 */
	private static final long TOKEN_LEEWAY_MS = 60_000L;

	/** 默认轮询间隔（毫秒）。测试可调整。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** 默认最大等待时间（毫秒）。测试可调整。 */
	static long MAX_WAIT_MS = 120000L;

	/** secretKey。 */
	private final String secretKey;

	/** 已缓存的 access_token。 */
	private volatile String cachedToken;

	/** token 过期时间戳（毫秒）。 */
	private volatile long tokenExpireAt;

	/** token 刷新锁。 */
	private final Object tokenLock = new Object();

	/**
	 * 构造客户端。
	 *
	 * @param config 配置：apiKey 为千帆 API Key，secretKey 需放在 extraHeaders("secretKey", ...)
	 */
	public BaiduImageClient(AiConfig config) {
		super(withDefaults(config));
		this.secretKey = config.extraHeaders().get(BaiduClient.SECRET_KEY_HEADER);
	}

	/**
	 * 客户端名称。
	 *
	 * @return "baidu-image"
	 */
	public String name() {
		return "baidu-image";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		// 百度鉴权走 URL 查询串 access_token，不走 Authorization 头
	}

	@Override
	public ImageResponse generate(ImageRequest request) {
		String token = getAccessToken();
		int[] wh = parseSize(request.size());
		JsonObject body = Json.object();
		body.put("prompt", request.prompt());
		body.put("width", wh[0]);
		body.put("height", wh[1]);
		body.put("image_num", request.n() != null ? request.n() : 1);
		PostResult submit = doPostRaw(
			"/rpc/2.0/ernievilg/v1/txt2imgv2?access_token=" + token, body);
		JsonObject resp = submit.json();
		int code = resp.optInt("code", 0);
		if (code != 0) {
			throw new AiApiException(200, String.valueOf(code),
				"baidu image submit failed: " + resp.optString("msg", ""), submit.rawBody());
		}
		JsonObject data = resp.getJsonObject("data");
		String taskId = data.getString("task_id");
		return pollResult(taskId, token);
	}

	/** 轮询任务结果，直到成功、失败或超时。 */
	private ImageResponse pollResult(String taskId, String token) {
		String path = "/rpc/2.0/ernievilg/v1/getImgv2?access_token=" + token;
		JsonObject body = Json.object();
		body.put("task_id", taskId);
		long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
		while (true) {
			PostResult poll = doPostRaw(path, body);
			JsonObject resp = poll.json();
			int code = resp.optInt("code", 0);
			if (code != 0) {
				throw new AiApiException(200, String.valueOf(code),
					"baidu image poll failed: " + resp.optString("msg", ""), poll.rawBody());
			}
			JsonObject data = resp.getJsonObject("data");
			int status = data.optInt("status", -1);
			if (status == 2) {
				String url = extractUrl(data);
				return ImageResponse.of(0, List.of(ImageResult.ofUrl(url)), poll.rawBody());
			}
			if (status == 3) {
				throw new AiApiException(200, null, "baidu image task failed", poll.rawBody());
			}
			// status 0=等待中，1=运行中，继续轮询
			if (System.currentTimeMillis() + POLL_INTERVAL_MS > deadline) {
				throw new AiTimeoutException("baidu image task polling timeout after "
					+ MAX_WAIT_MS + " ms");
			}
			sleepQuietly();
		}
	}

	/** 提取图片 URL：优先 data.img_url，其次 data.img_list[0].img_url。 */
	private static String extractUrl(JsonObject data) {
		String url = data.optString("img_url", null);
		if (url == null && data.has("img_list")) {
			JsonArray list = data.getJsonArray("img_list");
			if (!list.isEmpty()) {
				url = list.getJsonObject(0).optString("img_url", null);
			}
		}
		return url;
	}

	/** 解析尺寸为 [width, height]，支持 "1024x1024" / "1024*1024"，非法时默认 1024x1024。 */
	private static int[] parseSize(String size) {
		int w = 1024;
		int h = 1024;
		if (size != null && !size.isBlank()) {
			String[] parts = size.trim().toLowerCase().split("[x*×]");
			if (parts.length == 2) {
				try {
					w = Integer.parseInt(parts[0].trim());
					h = Integer.parseInt(parts[1].trim());
				} catch (NumberFormatException ignored) {
					// 解析失败回退默认尺寸
				}
			}
		}
		return new int[] { w, h };
	}

	/** 轮询间隔等待，可中断。 */
	private static void sleepQuietly() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("baidu image polling interrupted", ex);
		}
	}

	/** 获取有效 access_token：缓存未过期复用，否则双检锁重新换取。 */
	private String getAccessToken() {
		String token = this.cachedToken;
		if (token != null && System.currentTimeMillis() < this.tokenExpireAt - TOKEN_LEEWAY_MS) {
			return token;
		}
		synchronized (this.tokenLock) {
			if (this.cachedToken == null
				|| System.currentTimeMillis() >= this.tokenExpireAt - TOKEN_LEEWAY_MS) {
				fetchToken();
			}
			return this.cachedToken;
		}
	}

	/** 调 OAuth 接口换取 access_token 并更新缓存。 */
	private void fetchToken() {
		if (this.secretKey == null || this.secretKey.isBlank()) {
			throw new AiException("baidu secretKey is not configured (extraHeaders secretKey)");
		}
		String url = this.config.baseUrl() + "/oauth/2.0/token?grant_type=client_credentials"
			+ "&client_id=" + this.config.apiKey() + "&client_secret=" + this.secretKey;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.noBody())
			.build();
		HttpResponse<String> resp;
		try {
			resp = this.httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiTimeoutException("fetch baidu token failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("fetch baidu token interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			throw new AiAuthException("baidu token http " + status, resp.body());
		}
		JsonObject o = parseJson(resp.body());
		if (!o.has("access_token")) {
			throw new AiAuthException("baidu token response missing access_token", resp.body());
		}
		this.cachedToken = o.getString("access_token");
		long expiresInSec = o.get("expires_in").getAsLong();
		this.tokenExpireAt = System.currentTimeMillis() + expiresInSec * 1000L;
	}

	/** baseUrl 为空时补默认地址，其余配置通过 {@link AiConfig#withBaseUrl} 原样保留。 */
	private static AiConfig withDefaults(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(DEFAULT_BASE_URL);
	}
}
