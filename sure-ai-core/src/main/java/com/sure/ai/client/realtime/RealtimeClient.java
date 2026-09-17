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

/**
 * 实时语音全双工对话客户端抽象。
 *
 * <p>基于 WebSocket：连接后可同时发送/接收音频与文本，事件通过
 * {@link RealtimeEventListener} 回调。屏蔽平台差异。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface RealtimeClient {

	/**
	 * 建立 WebSocket 连接。
	 */
	void connect();

	/**
	 * 发送音频分片（内部按 base64 编码并封装为平台协议事件）。
	 *
	 * @param audio 音频分片字节
	 */
	void sendAudio(byte[] audio);

	/**
	 * 发送文本（如对话式输入、指令）。
	 *
	 * @param text 文本
	 */
	void sendText(String text);

	/**
	 * 关闭连接。
	 */
	void close();

	/**
	 * 是否已连接。
	 *
	 * @return 已连接返回 true
	 */
	boolean isConnected();
}
