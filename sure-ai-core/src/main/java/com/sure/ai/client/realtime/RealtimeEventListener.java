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
}
