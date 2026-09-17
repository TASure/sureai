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

package com.sure.ai.openai;

import java.net.http.HttpRequest;

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
 * OpenAI 官方平台批处理（Batches）客户端。
 *
 * <p>异步任务：</p>
 * <ol>
 *   <li>{@link #createBatch(BatchRequest)} 提交：{@code POST /v1/batches}，
 *       体 {@code {"input_file_id":..., "endpoint":"/v1/chat/completions",
 *       "completion_window":"24h", "metadata":...}}；</li>
 *   <li>{@link #getBatch(String)} 查询：{@code GET /v1/batches/{batch_id}}；</li>
 *   <li>{@link #waitForCompletion(String, long)} 轮询直至 completed / failed / 超时。</li>
 * </ol>
 *
 * <p>OpenAI Batch 要求先把请求列表打包为 JSONL 上传至 {@code /v1/files} 取得
 * {@code input_file_id}，本客户端不自动封装文件：若只传 {@code requests} 而未提供
 * {@code inputFileId}，直接抛出 {@link AiException} 提示。</p>
 *
 * <p>状态枚举：validating / failed / in_progress / finalizing / completed /
 * expired / cancelling / cancelled。</p>
 *
 * <p>官方文档：<a href="https://platform.openai.com/docs/api-reference/batch">Batch</a></p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class OpenAiBatchClient extends AbstractAiClient implements BatchClient {

	/** OpenAI 官方默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

	/** 批处理接口路径（相对 baseUrl）。 */
	private static final String BATCHES_PATH = "/batches";

	/** 缺省 endpoint：Chat 补全。 */
	public static final String ENDPOINT_CHAT = "/v1/chat/completions";

	/** 缺省完成时间窗。 */
	public static final String DEFAULT_COMPLETION_WINDOW = "24h";

	/** 默认轮询间隔（毫秒），测试可调整。 */
	static long POLL_INTERVAL_MS = 2000L;

	/**
	 * 构造客户端，baseUrl 为空时使用 {@link #DEFAULT_BASE_URL}。
	 *
	 * @param config 配置
	 */
	public OpenAiBatchClient(AiConfig config) {
		super(OpenAiClient.applyDefaultBaseUrl(config, DEFAULT_BASE_URL));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "openai-batch"
	 */
	public String name() {
		return "openai-batch";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
		if (cfg.organization() != null && !cfg.organization().isBlank()) {
			requestBuilder.header("OpenAI-Organization", cfg.organization());
		}
	}

	@Override
	public BatchResponse createBatch(BatchRequest request) {
		String inputFileId = request.inputFileId();
		if (inputFileId == null || inputFileId.isBlank()) {
			throw new AiException(
				"OpenAI Batch requires input_file_id; upload JSONL to /v1/files first");
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
}
