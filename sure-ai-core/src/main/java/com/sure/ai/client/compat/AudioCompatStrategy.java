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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.Segment;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.Word;

/**
 * Audio 能力域策略：TTS {@code /audio/speech}（二进制音频响应）与
 * STT {@code /audio/transcriptions}（multipart 上传 + 分段/词级时间戳解析）。
 *
 * @author sureai
 * @since 1.4.0
 */
final class AudioCompatStrategy {

	/** 持有外层客户端引用，用于二进制/multipart 传输与路径字段。 */
	private final OpenAiCompatClient client;

	/**
	 * @param client 外层 OpenAI 兼容客户端
	 */
	AudioCompatStrategy(OpenAiCompatClient client) {
		this.client = client;
	}

	// ==================== 语音合成 TTS ====================

	/** TTS 入口：返回音频二进制。 */
	TtsResponse synthesize(TtsRequest request) {
		JsonObject body = buildTtsBody(request);
		byte[] audio = this.client.transportPostBinary(this.client.ttsPath, body);
		String format = request.responseFormat() == null ? "mp3" : request.responseFormat();
		return TtsResponse.ofAudio(audio, format);
	}

	/** 构造 TTS 请求体。 */
	private JsonObject buildTtsBody(TtsRequest req) {
		JsonObject body = Json.object();
		body.put("model", req.model());
		body.put("input", req.input());
		body.put("voice", req.voice());
		CompatJson.putIfNotNull(body, "response_format", req.responseFormat());
		CompatJson.putIfNotNull(body, "speed", req.speed());
		CompatJson.putIfNotNull(body, "instructions", req.instructions());
		for (Map.Entry<String, Object> e : req.extra().entrySet()) {
			body.put(e.getKey(), Json.toElement(e.getValue()));
		}
		return body;
	}

	// ==================== 语音识别 STT ====================

	/** STT 入口：multipart 上传音频并解析转写结果。 */
	SttResponse transcribe(SttRequest request) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("model", request.model());
		if (request.language() != null) {
			fields.put("language", request.language());
		}
		String respFormat = request.responseFormat() == null ? "verbose_json"
			: request.responseFormat();
		fields.put("response_format", respFormat);
		if (request.temperature() != null) {
			fields.put("temperature", String.valueOf(request.temperature()));
		}
		if (request.prompt() != null) {
			fields.put("prompt", request.prompt());
		}
		String fileName = request.fileName() == null ? "audio.mp3" : request.fileName();
		String contentType = request.contentType() == null ? "audio/mpeg" : request.contentType();
		var result = this.client.transportMultipart(this.client.sttPath, fields,
			"file", fileName, contentType, request.audioData());
		return parseSttResponse(result.json(), result.rawBody());
	}

	/** 解析 STT 响应：{text, language, duration, segments:[], words:[]}。 */
	private SttResponse parseSttResponse(JsonObject resp, String rawJson) {
		String text = resp.optString("text", "");
		String language = resp.optString("language", null);
		Double duration = resp.has("duration") ? resp.get("duration").getAsDouble() : null;
		List<Segment> segments = new ArrayList<>();
		JsonArray segArr = resp.has("segments") ? resp.getJsonArray("segments") : null;
		if (segArr != null) {
			for (int i = 0; i < segArr.size(); i++) {
				JsonObject s = segArr.getJsonObject(i);
				List<Word> words = new ArrayList<>();
				JsonArray wordArr = s.has("words") ? s.getJsonArray("words") : null;
				if (wordArr != null) {
					for (int j = 0; j < wordArr.size(); j++) {
						JsonObject w = wordArr.getJsonObject(j);
						words.add(Word.of(
							w.optString("word", ""),
							w.optDouble("start", 0.0),
							w.optDouble("end", 0.0)));
					}
				}
				segments.add(Segment.of(
					s.optInt("id", i),
					s.optDouble("start", 0.0),
					s.optDouble("end", 0.0),
					s.optString("text", ""),
					words));
			}
		}
		List<Word> words = new ArrayList<>();
		JsonArray wordArr = resp.has("words") ? resp.getJsonArray("words") : null;
		if (wordArr != null) {
			for (int i = 0; i < wordArr.size(); i++) {
				JsonObject w = wordArr.getJsonObject(i);
				words.add(Word.of(
					w.optString("word", ""),
					w.optDouble("start", 0.0),
					w.optDouble("end", 0.0)));
			}
		}
		return SttResponse.of(text, language, duration, segments, words, rawJson);
	}
}
