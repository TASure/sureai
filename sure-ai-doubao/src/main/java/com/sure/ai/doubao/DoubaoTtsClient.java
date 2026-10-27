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

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.tool.codec.Base64Util;

/**
 * 豆包语音合成（Seed-TTS）客户端。
 *
 * <p>与方舟推理端点完全不同，需独立实现：</p>
 * <ul>
 *   <li>端点：{@code POST https://openspeech.bytedance.com/api/v3/tts/create}</li>
 *   <li>鉴权：非 Bearer，使用 {@code X-Api-Key: {apiKey}} 与
 *       {@code X-Api-Resource-Id: seed-tts-2.0}</li>
 *   <li>响应：JSON {@code {code:0, data:"<base64音频>", message:"success"}}，
 *       需将 {@code data} base64 解码为音频字节</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public class DoubaoTtsClient extends AbstractAiClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://openspeech.bytedance.com";

	/** TTS 创建接口路径。 */
	public static final String CREATE_PATH = "/api/v3/tts/create";

	/** X-Api-Key 请求头名。 */
	public static final String API_KEY_HEADER = "X-Api-Key";

	/** X-Api-Resource-Id 请求头名。 */
	public static final String RESOURCE_ID_HEADER = "X-Api-Resource-Id";

	/**
	 * 构造客户端；baseUrl 为空时使用豆包语音默认地址。
	 *
	 * @param config 配置
	 */
	public DoubaoTtsClient(AiConfig config) {
		super(withDefaultBaseUrl(config));
	}

	/**
	 * 客户端名称。
	 *
	 * @return "doubao-tts"
	 */
	public String name() {
		return "doubao-tts";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header(API_KEY_HEADER, cfg.apiKey());
		requestBuilder.header(RESOURCE_ID_HEADER, DoubaoModels.SEED_TTS_2_0);
	}

	/**
	 * 语音合成：POST JSON，解析 base64 音频。
	 *
	 * @param request TTS 请求
	 * @return 语音响应（二进制音频）
	 */
	public TtsResponse synthesize(TtsRequest request) {
		JsonObject body = Json.object();
		JsonObject reqParams = Json.object();
		reqParams.put("text", request.input());
		reqParams.put("speaker", request.voice());
		JsonObject audioParams = Json.object();
		audioParams.put("format",
			request.responseFormat() == null ? "mp3" : request.responseFormat());
		int sampleRate = request.sampleRate() == null ? 24000 : request.sampleRate();
		audioParams.put("sample_rate", sampleRate);
		reqParams.put("audio_params", audioParams);
		body.put("req_params", reqParams);

		PostResult result = doPostRaw(CREATE_PATH, body);
		JsonObject resp = result.json();
		int code = resp.optInt("code", 0);
		if (code != 0) {
			throw new AiApiException(200, String.valueOf(code),
				"doubao tts failed: " + resp.optString("message", ""), result.rawBody());
		}
		String b64 = resp.optString("data", "");
		byte[] audio = Base64Util.decode(b64);
		String format = request.responseFormat() == null ? "mp3" : request.responseFormat();
		return TtsResponse.ofAudio(audio, format);
	}

	/** baseUrl 为空时补默认地址，其余配置通过 {@link AiConfig#withBaseUrl} 原样保留。 */
	private static AiConfig withDefaultBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(DEFAULT_BASE_URL);
	}
}
