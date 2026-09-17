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
package com.sure.ai.gemini;

import java.net.URI;
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
 * Google Gemini Live（BidiGenerateContent，WebSocket 全双工语音对话）客户端。
 *
 * <p>协议要点：</p>
 * <ul>
 *   <li>端点：{@code wss://generativelanguage.googleapis.com/ws/...BidiGenerateContent?key=<apiKey>}，
 *       鉴权走 URL 查询参数；</li>
 *   <li>建连后首次需发送 {@code {"setup":{"model":"models/..."}}}，之后才能收发；</li>
 *   <li>下行按顶层 oneof 字段路由：{@code serverContent.modelTurn.parts[]} 中
 *       {@code inlineData.data}（base64 音频）回调 onAudio、{@code text} 回调 onTranscript；
 *       {@code error}/{@code toolCallCancellation} 回调 onError；其余回调 onEvent。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public class GeminiRealtimeClient extends AbstractRealtimeClient {

	/** 默认 WSS 主机。 */
	public static final String DEFAULT_WS_HOST = "wss://generativelanguage.googleapis.com";

	/** BidiGenerateContent WS 路径。 */
	public static final String WS_PATH = "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent";

	/** 实时模型（不含 models/ 前缀）。 */
	private final String model;

	/** setup 是否已发送（首次 connect 后发一次）。 */
	private boolean setupSent;

	/**
	 * 生产构造：使用 JDK WebSocket 默认连接器（Gemini 鉴权在 URL，无额外头）。
	 *
	 * @param config       连接配置
	 * @param model        实时模型（如 gemini-2.0-flash-exp）
	 * @param eventListener 事件监听器
	 */
	public GeminiRealtimeClient(AiConfig config, String model, RealtimeEventListener eventListener) {
		this(config, model, new DefaultRealtimeConnector(null), eventListener);
	}

	/**
	 * 测试/可注入构造。
	 *
	 * @param config       连接配置
	 * @param model        实时模型
	 * @param connector    WebSocket 连接器
	 * @param eventListener 事件监听器
	 */
	GeminiRealtimeClient(AiConfig config, String model, RealtimeConnector connector,
			RealtimeEventListener eventListener) {
		super(config, connector, eventListener);
		this.model = model;
	}

	/**
	 * 客户端名称。
	 *
	 * @return "gemini-realtime"
	 */
	public String name() {
		return "gemini-realtime";
	}

	@Override
	public void connect() {
		super.connect();
		if (!this.setupSent) {
			JsonObject setup = Json.object();
			JsonObject setupObj = Json.object();
			setupObj.put("model", "models/" + this.model);
			setup.set("setup", setupObj);
			super.sendText(setup.toString());
			this.setupSent = true;
		}
	}

	@Override
	protected URI buildUri() {
		String base = this.config.baseUrl();
		String host = (base == null || base.isBlank())
			? DEFAULT_WS_HOST
			: base.trim().replaceFirst("^https?://", "wss://");
		if (host.endsWith("/")) {
			host = host.substring(0, host.length() - 1);
		}
		return URI.create(host + WS_PATH + "?key=" + this.config.apiKey());
	}

	@Override
	protected void handleMessage(String message) {
		JsonObject o = Json.parse(message).getAsJsonObject();
		if (o.has("serverContent")) {
			JsonObject serverContent = o.getJsonObject("serverContent");
			JsonObject modelTurn = serverContent.has("modelTurn")
				? serverContent.getJsonObject("modelTurn") : null;
			if (modelTurn != null && modelTurn.has("parts")) {
				JsonArray parts = modelTurn.getJsonArray("parts");
				for (int i = 0; i < parts.size(); i++) {
					JsonObject p = parts.getJsonObject(i);
					if (p.has("text") && !p.get("text").isNull()) {
						this.eventListener.onTranscript(p.getString("text"));
					}
					if (p.has("inlineData") && !p.get("inlineData").isNull()) {
						JsonObject inline = p.getJsonObject("inlineData");
						String b64 = inline.optString("data", "");
						this.eventListener.onAudio(Base64.getDecoder().decode(b64));
					}
				}
			}
			return;
		}
		if (o.has("error")) {
			JsonElement err = o.get("error");
			String msg = err.isObject() ? err.getAsJsonObject().optString("message", null)
				: (err.isNull() ? null : err.getAsString());
			this.eventListener.onError(msg == null ? message : msg);
			return;
		}
		if (o.has("toolCallCancellation")) {
			this.eventListener.onError("toolCallCancellation");
			return;
		}
		this.eventListener.onEvent(topLevelKey(o), message);
	}

	/** 取顶层 oneof 字段名作为事件类型。 */
	private static String topLevelKey(JsonObject o) {
		var it = o.keySet().iterator();
		return it.hasNext() ? it.next() : "unknown";
	}

	@Override
	public void sendText(String text) {
		JsonObject msg = Json.object();
		JsonObject realtimeInput = Json.object();
		realtimeInput.put("text", text);
		msg.set("realtimeInput", realtimeInput);
		super.sendText(msg.toString());
	}

	@Override
	protected void sendAudioBase64(String base64Audio) {
		JsonObject msg = Json.object();
		JsonObject realtimeInput = Json.object();
		JsonObject inlineData = Json.object();
		inlineData.put("mimeType", "audio/pcm;rate=16000");
		inlineData.put("data", base64Audio);
		JsonObject chunk = Json.object();
		chunk.set("inlineData", inlineData);
		JsonArray chunks = Json.array();
		chunks.add(chunk);
		realtimeInput.set("chunks", chunks);
		msg.set("realtimeInput", realtimeInput);
		super.sendText(msg.toString());
	}
}
