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

package com.sure.ai.examples;

import java.nio.charset.StandardCharsets;

import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;

/**
 * 语音（TTS/STT）使用示例。
 *
 * <p>演示 OpenAI TTS（文本→二进制音频）与 STT（音频→转写文本）的调用。
 * TTS 响应为二进制音频字节流（{@link TtsResponse#audioLength()} 可获取大小），
 * STT 请求需传入音频二进制数据（{@code byte[]}），响应含转写文本及可选的段/词级时间戳。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class AudioDemo {

	private AudioDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		demoTts();
		demoStt();
	}

	/** OpenAI TTS：文本→二进制音频。 */
	private static void demoTts() {
		System.out.println("=== OpenAI TTS (tts-1) ===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			TtsResponse resp = OpenAiUtil.tts(OpenAiModels.TTS_1,
				"你好，欢迎使用 sureai 语音合成。", "alloy");
			System.out.println("  音频格式：" + resp.format());
			System.out.println("  音频字节数：" + resp.audioLength());
			if (resp.url() != null) {
				System.out.println("  音频 URL：" + resp.url());
			}
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}

	/** OpenAI STT：音频→转写文本（示例用模拟音频数据，实际使用时传入真实音频文件字节）。 */
	private static void demoStt() {
		System.out.println("=== OpenAI STT (whisper-1) ===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			// 示例：构造模拟音频数据；实际使用时读取音频文件字节
			byte[] fakeAudio = "RIFF....WAVE....data....".getBytes(StandardCharsets.UTF_8);
			SttResponse resp = OpenAiUtil.stt(OpenAiModels.WHISPER_1, fakeAudio);
			System.out.println("  转写文本：" + resp.text());
			if (resp.language() != null) {
				System.out.println("  检测语言：" + resp.language());
			}
			if (resp.duration() != null) {
				System.out.println("  音频时长：" + resp.duration() + "s");
			}
			if (!resp.segments().isEmpty()) {
				System.out.println("  段数：" + resp.segments().size());
			}
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
