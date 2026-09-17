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

import java.net.URI;
import java.util.Map;
import java.util.Base64;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.realtime.AbstractRealtimeClient;
import com.sure.ai.client.realtime.DefaultRealtimeConnector;
import com.sure.ai.client.realtime.RealtimeConnector;
import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * OpenAI Realtime（WebSocket 全双工语音对话）客户端。
 *
 * <p>协议要点：</p>
 * <ul>
 *   <li>端点：{@code wss://api.openai.com/v1/realtime?model=<model>}；</li>
 *   <li>鉴权：握手头 {@code Authorization: Bearer <apiKey>}；</li>
 *   <li>下行事件按顶层 {@code type} 路由：{@code response.output_audio.delta}
 *       的 {@code delta} 为 base64 音频，{@code response.audio_transcript.delta} 与
 *       {@code conversation.item.input_audio_transcription.completed} 为转写；</li>
 *   <li>上行：文本先发 {@code conversation.item.create} 再 {@code response.create}；
 *       音频发 {@code input_audio_buffer.append}。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public class OpenAiRealtimeClient extends AbstractRealtimeClient {

	/** 默认 WSS 基础地址（不含 trailing slash）。 */
	public static final String DEFAULT_WSS_BASE = "wss://api.openai.com/v1";

	/** 实时模型。 */
	private final String model;

	/**
	 * 生产构造：使用 JDK WebSocket 默认连接器，携带 Bearer 鉴权头。
	 *
	 * @param config       连接配置
	 * @param model        实时模型（如 gpt-4o-realtime-preview）
	 * @param eventListener 事件监听器
	 */
	public OpenAiRealtimeClient(AiConfig config, String model, RealtimeEventListener eventListener) {
		this(config, model, new DefaultRealtimeConnector(
			Map.of("Authorization", "Bearer " + config.apiKey())), eventListener);
	}

	/**
	 * 测试/可注入构造：使用给定连接器（如 FakeRealtimeConnector）。
	 *
	 * @param config       连接配置
	 * @param model        实时模型
	 * @param connector    WebSocket 连接器
	 * @param eventListener 事件监听器
	 */
	OpenAiRealtimeClient(AiConfig config, String model, RealtimeConnector connector,
			RealtimeEventListener eventListener) {
		super(config, connector, eventListener);
		this.model = model;
	}

	/**
	 * 客户端名称。
	 *
	 * @return "openai-realtime"
	 */
	public String name() {
		return "openai-realtime";
	}

	@Override
	protected URI buildUri() {
		String base = this.config.baseUrl();
		String wsBase = (base == null || base.isBlank())
			? DEFAULT_WSS_BASE
			: base.trim().replaceFirst("^https?://", "wss://");
		if (wsBase.endsWith("/")) {
			wsBase = wsBase.substring(0, wsBase.length() - 1);
		}
		return URI.create(wsBase + "/realtime?model=" + this.model);
	}

	@Override
	protected void handleMessage(String message) {
		JsonObject o = Json.parse(message).getAsJsonObject();
		String type = o.optString("type", "");
		switch (type) {
			case "response.output_audio.delta": {
				String b64 = o.optString("delta", "");
				this.eventListener.onAudio(Base64.getDecoder().decode(b64));
				break;
			}
			case "response.audio_transcript.delta":
				this.eventListener.onTranscript(o.optString("delta", ""));
				break;
			case "conversation.item.input_audio_transcription.completed":
				this.eventListener.onTranscript(o.optString("transcript", ""));
				break;
			case "error": {
				String msg = null;
				if (o.has("error")) {
					JsonElement err = o.get("error");
					if (err.isObject()) {
						msg = err.getAsJsonObject().optString("message", null);
					} else if (!err.isNull()) {
						msg = err.getAsString();
					}
				}
				this.eventListener.onError(msg == null ? message : msg);
				break;
			}
			default:
				this.eventListener.onEvent(type, message);
		}
	}

	@Override
	public void sendText(String text) {
		JsonObject item = Json.object();
		item.put("type", "message");
		item.put("role", "user");
		JsonArray content = Json.array();
		JsonObject part = Json.object();
		part.put("type", "input_text");
		part.put("text", text);
		content.add(part);
		item.put("content", content);

		JsonObject create = Json.object();
		create.put("type", "conversation.item.create");
		create.set("item", item);
		super.sendText(create.toString());

		JsonObject trigger = Json.object();
		trigger.put("type", "response.create");
		super.sendText(trigger.toString());
	}

	@Override
	protected void sendAudioBase64(String base64Audio) {
		JsonObject o = Json.object();
		o.put("type", "input_audio_buffer.append");
		o.put("audio", base64Audio);
		super.sendText(o.toString());
	}
}
