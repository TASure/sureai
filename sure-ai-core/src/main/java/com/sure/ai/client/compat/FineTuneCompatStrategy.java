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

package com.sure.ai.client.compat;

import java.util.LinkedHashMap;
import java.util.Map;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;

/**
 * FineTune 能力域策略：{@code /fine_tuning/jobs} 创建/查询、{@code /files} 训练文件上传，
 * 以及微调任务响应解析。
 *
 * <p>响应解析入口 {@link #parseResponse} 被外层 protected {@code parseFineTuneResponse}
 * 委托——平台子类（如 Azure）覆写 {@code getFineTune} 后仍可复用此解析逻辑。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
final class FineTuneCompatStrategy {

	/** 持有外层客户端引用，用于传输、路径字段与路径编码。 */
	private final OpenAiCompatClient client;

	/**
	 * @param client 外层 OpenAI 兼容客户端
	 */
	FineTuneCompatStrategy(OpenAiCompatClient client) {
		this.client = client;
	}

	/** 创建微调任务。 */
	FineTuneResponse createFineTune(FineTuneRequest request) {
		JsonObject body = Json.object();
		body.put("model", request.model());
		body.put("training_file", request.trainingFileId());
		if (request.hyperparameters() != null) {
			body.put("hyperparameters", Json.toElement(request.hyperparameters()));
		}
		if (request.suffix() != null) {
			body.put("suffix", request.suffix());
		}
		for (Map.Entry<String, Object> e : request.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		var result = this.client.transportPostRaw(this.client.fineTunePath, body);
		return parseResponse(result.json(), result.rawBody());
	}

	/** 查询微调任务详情。 */
	FineTuneResponse getFineTune(String jobId) {
		var result = this.client.transportGetRaw(
			this.client.fineTunePath + "/" + this.client.encodeSegment(jobId));
		return parseResponse(result.json(), result.rawBody());
	}

	/** 上传训练文件，返回文件 id。 */
	String uploadTrainingFile(String fileName, byte[] content) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("purpose", "fine-tune");
		var result = this.client.transportMultipart(this.client.filesPath, fields,
			"file", fileName, "application/octet-stream", content);
		String id = result.json().optString("id", null);
		if (id == null || id.isBlank()) {
			throw new AiException("file id not found in upload response: " + result.rawBody());
		}
		return id;
	}

	/** 解析微调任务响应（默认实现，由外层 protected parseFineTuneResponse 委托）。 */
	FineTuneResponse parseResponse(JsonObject resp, String rawJson) {
		Long createdAt = resp.has("created_at") ? resp.get("created_at").getAsLong() : null;
		Long completedAt = null;
		if (resp.has("finished_at")) {
			completedAt = resp.get("finished_at").getAsLong();
		} else if (resp.has("completed_at")) {
			completedAt = resp.get("completed_at").getAsLong();
		}
		String error = null;
		if (resp.has("error") && !resp.get("error").isNull()) {
			JsonObject err = resp.getJsonObject("error");
			error = err.optString("message", null);
			if (error == null) {
				error = resp.optString("error", null);
			}
		}
		return FineTuneResponse.of(resp.optString("id", null), resp.optString("status", null),
			resp.optString("model", null), resp.optString("fine_tuned_model", null),
			createdAt, completedAt, error, rawJson);
	}
}
