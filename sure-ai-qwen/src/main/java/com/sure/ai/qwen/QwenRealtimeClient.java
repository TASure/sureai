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
package com.sure.ai.qwen;

import java.net.URI;
import java.util.Base64;
import java.util.Map;

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
 * 通义千问 DashScope 实时语音（omni 系列）客户端。
 *
 * <p>协议要点：</p>
 * <ul>
 *   <li>端点：{@code wss://dashscope.aliyuncs.com/api-ws/v1/inference?model=<model>}；</li>
 *   <li>鉴权：握手头 {@code Authorization: Bearer <apiKey>}；</li>
 *   <li>下行按顶层 {@code type} 路由：{@code output.audio.delta} 的 base64 音频回调 onAudio，
 *       {@code output.text}/{@code transcript} 回调 onTranscript，{@code error} 回调 onError。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenRealtimeClient extends AbstractRealtimeClient {

	/** 默认 WSS 基础地址。 */
	public static final String DEFAULT_WSS_BASE = "wss://dashscope.aliyuncs.com/api-ws/v1";

	/** 实时模型。 */
	private final String model;

	/**
	 * 生产构造：JDK WebSocket 默认连接器，携带 Bearer 鉴权头。
	 *
	 * @param config       连接配置
	 * @param model        实时模型（如 omni-plus-realtime）
	 * @param eventListener 事件监听器
	 */
	public QwenRealtimeClient(AiConfig config, String model, RealtimeEventListener eventListener) {
		this(config, model, new DefaultRealtimeConnector(
			Map.of("Authorization", "Bearer " + config.apiKey())), eventListener);
	}

	/**
	 * 测试/可注入构造。
	 *
	 * @param config       连接配置
	 * @param model        实时模型
	 * @param connector    WebSocket 连接器
	 * @param eventListener 事件监听器
	 */
	QwenRealtimeClient(AiConfig config, String model, RealtimeConnector connector,
			RealtimeEventListener eventListener) {
		super(config, connector, eventListener);
		this.model = model;
	}

	/**
	 * 客户端名称。
	 *
	 * @return "qwen-realtime"
	 */
	public String name() {
		return "qwen-realtime";
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
		return URI.create(wsBase + "/inference?model=" + this.model);
	}

	@Override
	protected void handleMessage(String message) {
		JsonObject o = Json.parse(message).getAsJsonObject();
		String type = o.optString("type", "");
		switch (type) {
			case "output.audio.delta": {
				String b64 = o.optString("delta", o.optString("audio", ""));
				this.eventListener.onAudio(Base64.getDecoder().decode(b64));
				break;
			}
			case "output.text":
			case "transcript":
				this.eventListener.onTranscript(o.optString("output",
					o.optString("text", o.optString("transcript", ""))));
				break;
			case "error": {
				String msg = extractError(o);
				this.eventListener.onError(msg);
				break;
			}
			default:
				this.eventListener.onEvent(type, message);
		}
	}

	/** 从 error 事件提取可读消息。 */
	private static String extractError(JsonObject o) {
		if (o.has("error")) {
			JsonElement err = o.get("error");
			if (err.isObject()) {
				return err.getAsJsonObject().optString("message", o.toString());
			}
			if (!err.isNull()) {
				return err.getAsString();
			}
		}
		String msg = o.optString("message", null);
		return msg == null ? o.toString() : msg;
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
