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

package com.sure.ai.anthropic;

import java.net.http.HttpRequest;
import java.util.UUID;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.BatchClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

/**
 * Anthropic Message Batches 客户端（{@code /v1/messages/batches}）。
 *
 * <p>异步批处理：</p>
 * <ol>
 *   <li>{@link #createBatch(BatchRequest)} 提交，请求体为
 *       {@code {"requests":[{"custom_id":...,"params":{"model":...,"max_tokens":...,"messages":...}}]}}；</li>
 *   <li>{@link #getBatch(String)} 查询，解析 {@code processing_status}
 *       （{@code in_progress}/{@code ended}）与 {@code request_counts}；</li>
 *   <li>{@link #waitForCompletion(String, long)} 每 2s 轮询直至终态或超时。</li>
 * </ol>
 *
 * <p>复用 {@link AnthropicClient#serializeMessage(ChatMessage)} 的消息序列化逻辑。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class AnthropicBatchClient extends AbstractAiClient implements BatchClient {

	/** Messages 批处理接口路径。 */
	private static final String BATCHES_PATH = "/v1/messages/batches";

	/** 默认 anthropic-version 头值。 */
	private static final String DEFAULT_API_VERSION = "2023-06-01";

	/** 默认单请求 max_tokens。 */
	private static final int DEFAULT_MAX_TOKENS = 1024;

	/** 轮询间隔（毫秒）。 */
	private static final long POLL_INTERVAL_MS = 2000L;

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（baseUrl 为空时使用 Anthropic 官方地址）
	 */
	public AnthropicBatchClient(AiConfig config) {
		super(config);
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("x-api-key", cfg.apiKey());
		requestBuilder.header("anthropic-version", DEFAULT_API_VERSION);
	}

	/**
	 * 提交批处理任务。
	 *
	 * @param request 批处理请求（requests 为一组对话请求）
	 * @return 初始任务响应
	 */
	@Override
	public BatchResponse createBatch(BatchRequest request) {
		JsonObject body = Json.object();
		body.put("request_format", "messages");
		JsonArray requests = Json.array();
		for (ChatRequest cr : request.requests()) {
			JsonObject entry = Json.object();
			entry.put("custom_id", "req-" + UUID.randomUUID());
			JsonObject params = Json.object();
			params.put("model", cr.model() != null ? cr.model() : request.model());
			params.put("max_tokens", cr.maxTokens() != null ? cr.maxTokens() : DEFAULT_MAX_TOKENS);
			JsonArray messages = Json.array();
			for (ChatMessage m : cr.messages()) {
				messages.add(AnthropicClient.serializeMessage(m));
			}
			params.set("messages", messages);
			entry.set("params", params);
			requests.add(entry);
		}
		body.set("requests", requests);
		PostResult result = doPostRaw(BATCHES_PATH, body);
		return parseBatch(result.json(), result.rawBody());
	}

	/**
	 * 查询批处理任务状态。
	 *
	 * @param batchId 任务 ID
	 * @return 最新任务响应
	 */
	@Override
	public BatchResponse getBatch(String batchId) {
		PostResult result = doGetRaw(BATCHES_PATH + "/" + batchId);
		return parseBatch(result.json(), result.rawBody());
	}

	/**
	 * 阻塞轮询直至批处理进入终态（{@code processing_status} 不再为 {@code in_progress}）
	 * 或超时。
	 *
	 * @param batchId   任务 ID
	 * @param timeoutMs 超时毫秒
	 * @return 终态任务响应
	 */
	public BatchResponse waitForCompletion(String batchId, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (true) {
			BatchResponse resp = getBatch(batchId);
			if (!"in_progress".equals(resp.status()) && !"processing".equals(resp.status())) {
				return resp;
			}
			if (System.currentTimeMillis() >= deadline) {
				throw new AiException("batch wait timed out after " + timeoutMs + "ms: " + batchId);
			}
			sleepQuietly(POLL_INTERVAL_MS);
		}
	}

	/** 安静睡眠，中断时恢复中断标志。 */
	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("batch poll interrupted", ex);
		}
	}

	/** 解析 Anthropic batch 响应为统一 {@link BatchResponse}。 */
	private static BatchResponse parseBatch(JsonObject resp, String raw) {
		String id = resp.optString("id", null);
		String status = resp.optString("processing_status", "in_progress");
		long createdAt = resp.optLong("created_at", 0L);
		long completedAt = resp.optLong("ended_at", 0L);
		BatchResponse.RequestCounts counts = BatchResponse.RequestCounts.of(0, 0, 0);
		if (resp.has("request_counts")) {
			JsonObject rc = resp.getJsonObject("request_counts");
			int total = rc.optInt("total", rc.optInt("processing", 0)
				+ rc.optInt("succeeded", 0) + rc.optInt("errored", 0));
			int succeeded = rc.optInt("succeeded", 0);
			int errored = rc.optInt("errored", 0);
			counts = BatchResponse.RequestCounts.of(total, succeeded, errored);
		}
		String error = counts.failed() > 0 ? "request_counts.errored=" + counts.failed() : null;
		return new BatchResponse(id, status, createdAt, completedAt, counts, error, raw);
	}
}
