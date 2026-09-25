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

package com.sure.ai.doubao;

import java.net.http.HttpRequest;
import java.util.UUID;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.tool.codec.Base64Util;

/**
 * 豆包录音文件识别（BigASR）客户端：异步 submit + query 轮询。
 *
 * <p>火山 BigASR 原生端点：</p>
 * <ul>
 *   <li>提交：{@code POST .../api/v3/auc/bigmodel/submit}，
 *       头 {@code X-Api-Key} / {@code X-Api-Resource-Id: volc.bigasr.auc} /
 *       {@code X-Api-Request-Id} / {@code X-Api-Sequence:-1}，
 *       body {@code {audio:{url, format}, request:{model_name:"bigmodel"}}}，返回 {@code {code:0, id}}</li>
 *   <li>查询：{@code POST .../api/v3/auc/bigmodel/query}，body {@code {id}}，
 *       成功 {@code {code:0, result:{text, utterances:[]}}}</li>
 * </ul>
 *
 * <p>该接口要求音频公网 URL；为保持 {@code SttRequest.audioData} 接口一致，
 * 实现将音频 base64 编码为 {@code data:audio/...;base64,...} 放入 {@code audio.url}。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class DoubaoSttClient extends AbstractAiClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://openspeech.bytedance.com";

	/** 提交任务路径。 */
	public static final String SUBMIT_PATH = "/api/v3/auc/bigmodel/submit";

	/** 查询结果路径。 */
	public static final String QUERY_PATH = "/api/v3/auc/bigmodel/query";

	/** X-Api-Key 请求头名。 */
	public static final String API_KEY_HEADER = "X-Api-Key";

	/** X-Api-Resource-Id 请求头名。 */
	public static final String RESOURCE_ID_HEADER = "X-Api-Resource-Id";

	/** 轮询间隔（毫秒），包级可变便于测试。 */
	static long POLL_INTERVAL_MS = 2000L;

	/** 最大等待时间（毫秒），包级可变便于测试。 */
	static long MAX_WAIT_MS = 120000L;

	/**
	 * 构造客户端；baseUrl 为空时使用豆包语音默认地址。
	 *
	 * @param config 配置
	 */
	public DoubaoSttClient(AiConfig config) {
		super(withDefaultBaseUrl(config));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "doubao-stt"
	 */
	public String name() {
		return "doubao-stt";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header(API_KEY_HEADER, cfg.apiKey());
		requestBuilder.header(RESOURCE_ID_HEADER, DoubaoModels.VOLC_BIGASR_AUC);
		requestBuilder.header("X-Api-Request-Id", UUID.randomUUID().toString());
		requestBuilder.header("X-Api-Sequence", "-1");
	}

	/**
	 * 语音识别：submit → 轮询 query → 提取 result.text。
	 *
	 * @param request STT 请求（audioData 会被 base64 编码为 data URI）
	 * @return 识别响应
	 */
	public SttResponse transcribe(SttRequest request) {
		String format = detectFormat(request);
		String dataUri = "data:" + (request.contentType() == null ? "audio/mpeg" : request.contentType())
			+ ";base64," + Base64Util.encode(request.audioData());

		JsonObject submitBody = Json.object();
		JsonObject audio = Json.object();
		audio.put("url", dataUri);
		audio.put("format", format);
		submitBody.put("audio", audio);
		JsonObject reqObj = Json.object();
		reqObj.put("model_name", "bigmodel");
		submitBody.put("request", reqObj);

		PostResult submit = doPostRaw(SUBMIT_PATH, submitBody);
		checkCode(submit.json(), submit.rawBody());
		String taskId = submit.json().optString("id", null);
		if (taskId == null || taskId.isBlank()) {
			throw new AiException("doubao stt task id not found in response: " + submit.rawBody());
		}

		long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
		String lastRaw = submit.rawBody();
		while (System.currentTimeMillis() < deadline) {
			sleepQuietly();
			JsonObject qBody = Json.object();
			qBody.put("id", taskId);
			PostResult poll = doPostRaw(QUERY_PATH, qBody);
			lastRaw = poll.rawBody();
			checkCode(poll.json(), poll.rawBody());
			if (poll.json().has("result")) {
				JsonObject result = poll.json().getJsonObject("result");
				String text = result.optString("text", "");
				return SttResponse.ofText(text);
			}
			// 无 result 字段表示仍在处理中，继续轮询
		}
		throw new AiTimeoutException("doubao stt polling timed out after "
			+ MAX_WAIT_MS + "ms, last: " + lastRaw);
	}

	/** 校验业务 code，非 0 抛异常。 */
	private static void checkCode(JsonObject resp, String raw) {
		int code = resp.optInt("code", 0);
		if (code != 0) {
			throw new AiApiException(200, String.valueOf(code),
				"doubao stt failed: " + resp.optString("message", ""), raw);
		}
	}

	/** 从 contentType / fileName 推断音频格式后缀，默认 mp3。 */
	private static String detectFormat(SttRequest req) {
		String ct = req.contentType();
		if (ct != null) {
			if (ct.contains("wav")) {
				return "wav";
			}
			if (ct.contains("mpeg") || ct.contains("mp3")) {
				return "mp3";
			}
			if (ct.contains("ogg")) {
				return "ogg";
			}
			if (ct.contains("mp4")) {
				return "mp4";
			}
		}
		String name = req.fileName();
		if (name != null && name.contains(".")) {
			return name.substring(name.lastIndexOf('.') + 1);
		}
		return "mp3";
	}

	/** 轮询间隔等待，可中断。 */
	private static void sleepQuietly() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("doubao stt polling interrupted", ex);
		}
	}

	/** baseUrl 为空时补默认地址，其余配置通过 {@link AiConfig#withBaseUrl} 原样保留。 */
	private static AiConfig withDefaultBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(DEFAULT_BASE_URL);
	}
}
