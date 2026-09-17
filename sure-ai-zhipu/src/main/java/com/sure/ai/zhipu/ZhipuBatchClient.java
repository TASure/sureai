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
import java.util.Map;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.BatchClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;
import com.sure.ai.model.BatchResponse.RequestCounts;

/**
 * 智谱 AI 开放平台批处理（Batches）客户端。
 *
 * <p>协议与 OpenAI 一致，baseUrl 已含 {@code /api/paas/v4}，故端点为 {@code /batches}：</p>
 * <ul>
 *   <li>提交：{@code POST /batches}；</li>
 *   <li>查询：{@code GET /batches/{batch_id}}；</li>
 *   <li>{@link #waitForCompletion(String, long)} 轮询。</li>
 * </ul>
 *
 * <p>鉴权与 {@link ZhipuClient} 一致：用 {@code id.secret} 中的 secret 现场签发 HS256 JWT
 * 作为 Bearer token，并在有效期内缓存复用。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class ZhipuBatchClient extends AbstractAiClient implements BatchClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

	/** 批处理接口路径（相对 baseUrl）。 */
	private static final String BATCHES_PATH = "/batches";

	/** 缺省 endpoint：Chat 补全。 */
	public static final String ENDPOINT_CHAT = "/v1/chat/completions";

	/** 缺省完成时间窗。 */
	public static final String DEFAULT_COMPLETION_WINDOW = "24h";

	/** JWT 提前刷新的安全余量（毫秒）。 */
	private static final long REFRESH_SKEW_MS = 60_000L;

	/** 默认轮询间隔（毫秒），测试可调整。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** 已缓存的 Bearer token（含 "Bearer " 前缀）。 */
	private volatile String cachedToken;

	/** token 过期时间戳（毫秒）。 */
	private volatile long expireAt;

	/**
	 * 构造客户端；baseUrl 为空时使用智谱默认地址。
	 *
	 * @param config 配置（apiKey 必须是 {@code id.secret} 格式）
	 */
	public ZhipuBatchClient(AiConfig config) {
		super(withDefaultBaseUrl(config));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "zhipu-batch"
	 */
	public String name() {
		return "zhipu-batch";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", resolveToken(cfg.apiKey()));
	}

	@Override
	public BatchResponse createBatch(BatchRequest request) {
		String inputFileId = request.inputFileId();
		if (inputFileId == null || inputFileId.isBlank()) {
			throw new AiException(
				"Zhipu Batch requires input_file_id; upload JSONL to /files first");
		}
		JsonObject body = Json.object();
		body.put("input_file_id", inputFileId);
		Object endpointOverride = request.extra().get("endpoint");
		String endpoint = endpointOverride == null ? ENDPOINT_CHAT : String.valueOf(endpointOverride);
		body.put("endpoint", endpoint);
		String window = request.completionWindow() == null || request.completionWindow().isBlank()
			? DEFAULT_COMPLETION_WINDOW : request.completionWindow();
		body.put("completion_window", window);
		if (!request.metadata().isEmpty()) {
			body.put("metadata", Json.toElement(request.metadata()));
		}
		PostResult result = doPostRaw(BATCHES_PATH, body);
		return parseBatch(result.json(), result.rawBody());
	}

	@Override
	public BatchResponse getBatch(String batchId) {
		PostResult result = doGetRaw(BATCHES_PATH + "/" + batchId);
		return parseBatch(result.json(), result.rawBody());
	}

	/**
	 * 轮询任务直至成功、失败或超时。
	 *
	 * @param batchId   任务 ID
	 * @param timeoutMs 最大等待毫秒
	 * @return 成功终态响应
	 * @throws AiException       任务失败或轮询被中断
	 * @throws AiTimeoutException 超过 timeoutMs 仍未到终态
	 */
	public BatchResponse waitForCompletion(String batchId, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (true) {
			BatchResponse resp = getBatch(batchId);
			if (resp.isCompleted()) {
				return resp;
			}
			if (resp.isFailed()) {
				throw new AiException("batch " + batchId + " failed: " + resp.error());
			}
			if (System.currentTimeMillis() + POLL_INTERVAL_MS > deadline) {
				throw new AiTimeoutException("batch " + batchId + " polling timeout after "
					+ timeoutMs + " ms, last status=" + resp.status());
			}
			sleepQuietly();
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

	/** 轮询间隔等待，可中断。 */
	private static void sleepQuietly() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("batch polling interrupted", ex);
		}
	}

	/** 解析批处理响应：{id, status, created_at, completed_at, request_counts, error}。 */
	private static BatchResponse parseBatch(JsonObject resp, String rawJson) {
		String id = resp.optString("id", null);
		String status = resp.optString("status", null);
		long createdAt = resp.optLong("created_at", 0L);
		long completedAt = resp.optLong("completed_at", 0L);
		RequestCounts counts = null;
		if (resp.has("request_counts")) {
			JsonObject rc = resp.getJsonObject("request_counts");
			counts = new RequestCounts(rc.optInt("total", 0), rc.optInt("completed", 0),
				rc.optInt("failed", 0));
		}
		String error = parseError(resp.get("error"));
		return new BatchResponse(id, status, createdAt, completedAt, counts, error, rawJson);
	}

	/** error 字段可能为对象（{message}）、字符串或 null。 */
	private static String parseError(JsonElement errorEl) {
		if (errorEl == null || errorEl.isNull()) {
			return null;
		}
		if (errorEl.isObject()) {
			return errorEl.getAsJsonObject().optString("message", null);
		}
		return errorEl.getAsString();
	}

	/** baseUrl 为空时补默认地址，其余配置原样保留。 */
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
		if (config.organization() != null && !config.organization().isBlank()) {
			b.organization(config.organization());
		}
		for (Map.Entry<String, String> e : config.extraHeaders().entrySet()) {
			b.extraHeader(e.getKey(), e.getValue());
		}
		return b.build();
	}
}
