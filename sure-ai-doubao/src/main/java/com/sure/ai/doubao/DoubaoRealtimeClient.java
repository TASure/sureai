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

import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.realtime.AbstractRealtimeClient;
import com.sure.ai.client.realtime.DefaultRealtimeConnector;
import com.sure.ai.client.realtime.RealtimeConnector;
import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;

/**
 * 火山引擎（豆包）实时语音对话客户端。
 *
 * <p>协议要点：</p>
 * <ul>
 *   <li>端点：{@code wss://openspeech.bytedance.com/api/v3/auc/realtime}；</li>
 *   <li>鉴权：握手头 {@code X-Api-Key: <apiKey>} 与 {@code X-Api-Resource-Id: <resourceId>}
 *       （resourceId 可用 {@code extraHeaders("X-Api-Resource-Id", ...)} 覆盖）；</li>
 *   <li>下行 JSON 事件按 {@code type} 路由：{@code audio} 的 {@code data}（base64 音频）
 *       回调 onAudio，{@code transcript}/{@code text} 回调 onTranscript，{@code error} 回调 onError。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.2.0
 */
public class DoubaoRealtimeClient extends AbstractRealtimeClient {

	/** 默认 WSS 基础地址。 */
	public static final String DEFAULT_WSS_BASE = "wss://openspeech.bytedance.com";

	/** Realtime 接口路径。 */
	public static final String REALTIME_PATH = "/api/v3/auc/realtime";

	/** 默认 X-Api-Resource-Id（实时对话资源）。 */
	public static final String DEFAULT_RESOURCE_ID = "volc.seedasr.auc";

	/** X-Api-Key 头名。 */
	public static final String API_KEY_HEADER = "X-Api-Key";

	/** X-Api-Resource-Id 头名。 */
	public static final String RESOURCE_ID_HEADER = "X-Api-Resource-Id";

	/** 实时模型（用于日志/标识）。 */
	private final String model;

	/**
	 * 生产构造：使用 JDK WebSocket 默认连接器，携带 X-Api-Key / X-Api-Resource-Id 头。
	 *
	 * @param config       连接配置
	 * @param model        实时模型（如 ep-xxx 或模型 ID）
	 * @param eventListener 事件监听器
	 */
	public DoubaoRealtimeClient(AiConfig config, String model, RealtimeEventListener eventListener) {
		this(config, model, buildConnector(config), eventListener);
	}

	/**
	 * 测试/可注入构造。
	 *
	 * @param config       连接配置
	 * @param model        实时模型
	 * @param connector    WebSocket 连接器
	 * @param eventListener 事件监听器
	 */
	DoubaoRealtimeClient(AiConfig config, String model, RealtimeConnector connector,
			RealtimeEventListener eventListener) {
		super(config, connector, eventListener);
		this.model = model;
	}

	/** 构造携带豆包鉴权头的默认连接器。 */
	private static RealtimeConnector buildConnector(AiConfig config) {
		String resourceId = config.extraHeaders().getOrDefault(RESOURCE_ID_HEADER, DEFAULT_RESOURCE_ID);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put(API_KEY_HEADER, config.apiKey());
		headers.put(RESOURCE_ID_HEADER, resourceId);
		return new DefaultRealtimeConnector(headers);
	}

	/**
	 * 客户端名称。
	 *
	 * @return "doubao-realtime"
	 */
	public String name() {
		return "doubao-realtime";
	}

	/**
	 * 实时模型。
	 *
	 * @return 模型
	 */
	public String model() {
		return this.model;
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
		return URI.create(wsBase + REALTIME_PATH);
	}

	@Override
	protected void handleMessage(String message) {
		JsonObject o = Json.parse(message).getAsJsonObject();
		String type = o.optString("type", "");
		switch (type) {
			case "audio": {
				String b64 = o.optString("data", "");
				this.eventListener.onAudio(Base64.getDecoder().decode(b64));
				break;
			}
			case "transcript":
			case "text":
				this.eventListener.onTranscript(o.optString("text", o.optString("transcript", "")));
				break;
			case "error": {
				String msg = o.optString("message", null);
				this.eventListener.onError(msg == null ? message : msg);
				break;
			}
			default:
				this.eventListener.onEvent(type, message);
		}
	}

	@Override
	protected void sendAudioBase64(String base64Audio) {
		JsonObject o = Json.object();
		o.put("type", "audio");
		o.put("data", base64Audio);
		super.sendText(o.toString());
	}
}
