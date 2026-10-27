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

import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.grok.GrokClient;
import com.sure.ai.grok.GrokModels;
import com.sure.ai.grok.GrokUtil;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * Grok（xAI）平台使用示例。
 *
 * <p>演示：静态工具调用、Builder + Client、流式。</p>
 *
 * @author sureai
 * @since 1.3.0
 */
public final class GrokDemo {

	private GrokDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		String apiKey = System.getenv("SURE_AI_GROK_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("请设置 SURE_AI_GROK_API_KEY，获取地址：https://console.x.ai/");
			return;
		}
		try {
			demoStaticChat();
			demoClientChat();
			demoStream();
		} catch (Exception e) {
			System.out.println("Grok 示例执行异常：" + e.getMessage());
		}
	}

	private static void demoStaticChat() {
		System.out.println("=== 1. 静态工具对话 ===");
		ChatResponse resp = GrokUtil.chat(GrokModels.GROK_3_MINI, "你好，用一句话介绍你自己。");
		System.out.println(resp.firstText());
	}

	private static void demoClientChat() {
		System.out.println("=== 2. Builder + Client 对话 ===");
		String apiKey = System.getenv("SURE_AI_GROK_API_KEY");
		AiConfig config = AiConfig.builder().apiKey(apiKey).build();
		GrokClient client = new GrokClient(config);
		ChatResponse resp = client.chat(GrokModels.GROK_3_MINI, "用一句话回答：1+1等于几？");
		System.out.println(resp.firstText());
		client.close();
	}

	private static void demoStream() {
		System.out.println("=== 3. 流式对话 ===");
		ChatRequest req = ChatRequest.builder()
			.model(GrokModels.GROK_3_MINI)
			.messages(List.of(ChatMessage.user("用三个字说一种水果。")))
			.build();
		StringBuilder sb = new StringBuilder();
		GrokUtil.chatStream(req, new Consumer<ChatStreamChunk>() {
			@Override
			public void accept(ChatStreamChunk chunk) {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			}
		});
		System.out.println(sb);
	}
}
