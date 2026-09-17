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
 * Azure OpenAI 批处理（Batches）客户端。
 *
 * <p>协议与 OpenAI 官方一致，但适配 Azure 两处差异：</p>
 * <ul>
 *   <li><b>鉴权</b>：使用 {@code api-key: <apiKey>} 头（而非 {@code Authorization: Bearer}）；</li>
 *   <li><b>路径</b>：{@code /openai/v1/batches?api-version=...}，api-version 从
 *       {@code config.extraHeaders("api-version")} 读取，缺省 {@link AzureClient#DEFAULT_API_VERSION}。</li>
 * </ul>
 *
 * <p>异步任务：{@link #createBatch(BatchRequest)} 提交、{@link #getBatch(String)} 查询、
 * {@link #waitForCompletion(String, long)} 轮询。Azure Batch 同样要求先上传 JSONL 取得
 * {@code input_file_id}。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class AzureBatchClient extends AbstractAiClient implements BatchClient {

	/** 批处理接口路径前缀（相对 baseUrl）。 */
	private static final String BATCHES_PATH = "/openai/v1/batches";

	/** 缺省 endpoint：Chat 补全。 */
	public static final String ENDPOINT_CHAT = "/v1/chat/completions";

	/** 缺省完成时间窗。 */
	public static final String DEFAULT_COMPLETION_WINDOW = "24h";

	/** 默认轮询间隔（毫秒），测试可调整。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** api-version 查询参数值。 */
	private final String apiVersion;

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（baseUrl 经 resource 推导；api-version 经 extraHeaders 传入）
	 */
	public AzureBatchClient(AiConfig config) {
		super(AzureClient.normalizeBaseUrl(config));
		this.apiVersion = config.extraHeaders().getOrDefault("api-version",
			AzureClient.DEFAULT_API_VERSION);
	}

	/**
	 * 客户端名称。
	 *
	 * @return "azure-batch"
	 */
	public String name() {
		return "azure-batch";
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
	public BatchResponse createBatch(BatchRequest request) {
		String inputFileId = request.inputFileId();
		if (inputFileId == null || inputFileId.isBlank()) {
			throw new AiException(
				"Azure Batch requires input_file_id; upload JSONL to /openai/v1/files first");
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
		PostResult result = doPostRaw(batchesPath(""), body);
		return parseBatch(result.json(), result.rawBody());
	}

	@Override
	public BatchResponse getBatch(String batchId) {
		PostResult result = doGetRaw(batchesPath("/" + batchId));
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

	/** 拼接 Azure 批处理路径与 api-version 查询参数。 */
	private String batchesPath(String suffix) {
		return BATCHES_PATH + suffix + "?api-version=" + this.apiVersion;
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
