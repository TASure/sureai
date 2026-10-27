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

import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.openai.OpenAiRealtimeClient;
import com.sure.ai.openai.OpenAiUtil;

/**
 * Realtime 实时语音全双工对话使用示例。
 *
 * <p>演示通过 {@code OpenAiUtil.realtimeClient(model, listener)} 构建实时客户端、
 * 注册 {@link RealtimeEventListener} 回调，并展示 {@code sendText}/{@code sendAudio}/{@code close}
 * 的调用方式。</p>
 *
 * <p><b>注意</b>：Realtime 基于 WebSocket，{@code connect()} 会建立到平台的真实长连接并产生费用。
 * 本 Demo 仅展示 API 用法，<b>不调用 {@code connect()}</b>；如需实际对话，取消下方
 * {@code client.connect()} 的注释即可。</p>
 *
 * <p>检查环境变量 {@code SURE_AI_OPENAI_API_KEY}，缺失时打印提示并优雅跳过。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class RealtimeDemo {

	/** 实时对话模型（按需替换为你有权限的 realtime 模型）。 */
	private static final String MODEL = "gpt-4o-realtime-preview";

	private RealtimeDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== OpenAI Realtime 实时语音对话（API 用法演示）===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			// 1. 注册事件监听器：转写文本 / 回复音频 / 错误 / 关闭 / 原始事件
			RealtimeEventListener listener = new RealtimeEventListener() {
				@Override
				public void onTranscript(String text) {
					System.out.println("  [转写] " + text);
				}

				@Override
				public void onAudio(byte[] audio) {
					System.out.println("  [音频] 收到 " + audio.length + " 字节回复音频分片");
				}

				@Override
				public void onError(String error) {
					System.out.println("  [错误] " + error);
				}

				@Override
				public void onClose() {
					System.out.println("  [关闭] 连接已关闭");
				}

				@Override
				public void onEvent(String type, String rawJson) {
					System.out.println("  [事件] " + type + " -> " + rawJson);
				}
			};

			// 2. 构建实时客户端（单例，未建连）
			OpenAiRealtimeClient client = OpenAiUtil.realtimeClient(MODEL, listener);
			System.out.println("  已创建 Realtime 客户端，模型=" + MODEL + "，已连接=" + client.isConnected());

			// 3. 建立真实 WebSocket 连接（生产环境取消注释）。
			//    建连后即可 client.sendText("你好") 或 client.sendAudio(pcmChunks) 双向通话。
			// client.connect();
			// client.sendText("你好，用一句话介绍你自己。");
			// ... 通话期间持续收发音频分片 ...
			// client.close();

			System.out.println("  本 Demo 仅演示 API 用法，未调用 connect() 建立真实连接。");
			System.out.println("  如需实测：取消 main() 中 client.connect() 的注释。");
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
