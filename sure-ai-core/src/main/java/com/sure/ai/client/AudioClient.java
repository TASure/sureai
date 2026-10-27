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

package com.sure.ai.client;

import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;

/**
 * 语音客户端抽象：含 TTS（语音合成）与 STT（语音识别）两个方法族。
 *
 * <p>TTS 响应形态分两派：二进制音频流（{@link TtsResponse#audio()} 非空）或音频 URL
 * （{@link TtsResponse#url()} 非空），由平台协议决定。STT 统一返回转写文本
 * （{@link SttResponse#text()}），verbose 模式下含段/词级时间戳。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface AudioClient {

	/**
	 * 语音合成（TTS）。
	 *
	 * @param request 请求
	 * @return 响应（二进制音频或音频 URL）
	 */
	TtsResponse synthesize(TtsRequest request);

	/**
	 * 便捷语音合成：模型/文本/音色。
	 *
	 * @param model 模型名
	 * @param text  待合成文本
	 * @param voice 音色 ID
	 * @return 响应
	 */
	default TtsResponse synthesize(String model, String text, String voice) {
		return synthesize(TtsRequest.of(model, text, voice));
	}

	/**
	 * 语音识别（STT/转录）。
	 *
	 * @param request 请求（含音频二进制数据）
	 * @return 响应（含转写文本）
	 */
	SttResponse transcribe(SttRequest request);

	/**
	 * 便捷语音识别：模型 + 音频数据。
	 *
	 * @param model    模型名
	 * @param audioData 音频二进制数据
	 * @return 响应
	 */
	default SttResponse transcribe(String model, byte[] audioData) {
		return transcribe(SttRequest.of(model, audioData));
	}
}
