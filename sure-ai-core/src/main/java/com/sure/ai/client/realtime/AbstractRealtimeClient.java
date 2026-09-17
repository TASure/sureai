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

package com.sure.ai.client.realtime;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.concurrent.CompletionStage;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;

/**
 * 实时对话客户端抽象基类。
 *
 * <p>持有可注入的 {@link RealtimeConnector}，实现通用的 connect/sendText/sendAudio/close
 * 流程；平台子类覆盖 {@link #buildUri()}（构造含模型与鉴权参数的 WebSocket URL）与
 * {@link #handleMessage(String)}（解析平台事件并回调 {@link RealtimeEventListener}）。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public abstract class AbstractRealtimeClient implements RealtimeClient {

	/** 连接配置。 */
	protected final AiConfig config;

	/** 事件监听器。 */
	protected final RealtimeEventListener eventListener;

	/** WebSocket 连接器（可注入以 mock 测试）。 */
	private final RealtimeConnector connector;

	/** 当前 WebSocket，volatile 以保证可见性。 */
	private volatile WebSocket webSocket;

	/** 连接状态。 */
	private volatile boolean connected;

	/**
	 * 构造客户端。
	 *
	 * @param config       连接配置
	 * @param connector    WebSocket 连接器
	 * @param eventListener 事件监听器
	 */
	protected AbstractRealtimeClient(AiConfig config, RealtimeConnector connector,
			RealtimeEventListener eventListener) {
		this.config = config;
		this.connector = connector;
		this.eventListener = eventListener;
	}

	@Override
	public void connect() {
		URI uri = buildUri();
		this.webSocket = this.connector.connect(uri, new InternalListener());
		this.connected = true;
	}

	@Override
	public void sendText(String text) {
		requireConnected();
		this.webSocket.sendText(text, true);
	}

	@Override
	public void sendAudio(byte[] audio) {
		String b64 = Base64.getEncoder().encodeToString(audio);
		sendAudioBase64(b64);
	}

	/**
	 * 发送已 base64 编码的音频分片。默认直接发送原始 base64 文本，
	 * 平台子类可覆盖以封装为平台协议事件。
	 *
	 * @param base64Audio base64 编码音频
	 */
	protected void sendAudioBase64(String base64Audio) {
		sendText(base64Audio);
	}

	@Override
	public void close() {
		WebSocket ws = this.webSocket;
		if (ws != null) {
			try {
				ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
			} catch (RuntimeException ex) {
				this.eventListener.onError("close failed: " + ex.getMessage());
			}
		}
		this.connected = false;
		this.eventListener.onClose();
	}

	@Override
	public boolean isConnected() {
		return this.connected;
	}

	/**
	 * 构造 WebSocket 连接 URL（含模型/鉴权参数）。
	 *
	 * @return URI
	 */
	protected abstract URI buildUri();

	/**
	 * 解析收到的平台事件消息并回调监听器。
	 *
	 * @param message 原始消息文本
	 */
	protected abstract void handleMessage(String message);

	/** 校验已连接。 */
	private void requireConnected() {
		if (!this.connected || this.webSocket == null) {
			throw new AiException("realtime client is not connected");
		}
	}

	/** WebSocket 消息监听器：把文本帧转发到 handleMessage。 */
	private final class InternalListener implements WebSocket.Listener {

		@Override
		public void onOpen(WebSocket socket) {
			socket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
			try {
				handleMessage(data.toString());
			} catch (RuntimeException ex) {
				AbstractRealtimeClient.this.eventListener.onError("handle message failed: " + ex.getMessage());
			}
			socket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket socket, Throwable error) {
			AbstractRealtimeClient.this.connected = false;
			AbstractRealtimeClient.this.eventListener.onError(
				error.getMessage() == null ? error.toString() : error.getMessage());
		}

		@Override
		public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
			AbstractRealtimeClient.this.connected = false;
			AbstractRealtimeClient.this.eventListener.onClose();
			return null;
		}
	}
}
