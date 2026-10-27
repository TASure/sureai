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
 * 实时对话事件监听器。
 *
 * @author sureai
 * @since 0.2.0
 */
public interface RealtimeEventListener {

	/**
	 * 收到语音转写文本。
	 *
	 * @param text 转写文本
	 */
	void onTranscript(String text);

	/**
	 * 收到模型回复的音频分片。
	 *
	 * @param audio 音频分片字节
	 */
	void onAudio(byte[] audio);

	/**
	 * 发生错误。
	 *
	 * @param error 错误描述
	 */
	void onError(String error);

	/**
	 * 连接关闭。
	 */
	void onClose();

	/**
	 * 原始事件兜底回调（未识别类型的事件）。
	 *
	 * @param type    事件类型
	 * @param rawJson 原始事件 JSON
	 */
	void onEvent(String type, String rawJson);

	// ==== 以下为 2.4.0 新增的连接生命周期 / VAD / 中断事件，均为 default 空实现，
	// ==== 老实现者不覆写也不受影响（向后兼容）。

	/**
	 * 连接建立（WebSocket 握手成功）。每次成功建连（含首次与每次重连成功）均回调。
	 *
	 * @since 2.4.0
	 */
	default void onConnected() {
	}

	/**
	 * 连接断开（异常掉线；用户主动 {@code close()} 不触发本回调）。
	 *
	 * @param statusCode WebSocket 关闭码（异常掉线通常为 1006）
	 * @param reason     关闭原因
	 * @since 2.4.0
	 */
	default void onDisconnected(int statusCode, String reason) {
	}

	/**
	 * 即将发起第 {@code attempt} 次重连（等待 {@code delayMillis} 后执行）。
	 *
	 * @param attempt      本次是第几次重连（从 1 起）
	 * @param delayMillis  退避等待毫秒数
	 * @since 2.4.0
	 */
	default void onReconnecting(int attempt, long delayMillis) {
	}

	/**
	 * 重连成功（此前经历了 {@code attempts} 次失败尝试）。
	 *
	 * @param attempts 本次重连成功前已尝试的次数
	 * @since 2.4.0
	 */
	default void onReconnected(int attempts) {
	}

	/**
	 * 放弃重连（已达 {@code maxReconnectAttempts} 上限仍未成功）。
	 *
	 * @param attempts 累计尝试次数
	 * @since 2.4.0
	 */
	default void onReconnectFailed(int attempts) {
	}

	/**
	 * 服务端 VAD 检测到用户语音开始（speech started）。
	 *
	 * @since 2.4.0
	 */
	default void onSpeechStart() {
	}

	/**
	 * 服务端 VAD 检测到用户语音结束（speech stopped）。
	 *
	 * @since 2.4.0
	 */
	default void onSpeechStop() {
	}

	/**
	 * 模型输出被打断（barge-in / 中断）：用户抢话或服务端截断了正在播放的回复。
	 *
	 * @since 2.4.0
	 */
	default void onInterrupted() {
	}
}
