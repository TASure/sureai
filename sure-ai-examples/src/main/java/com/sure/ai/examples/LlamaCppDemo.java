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
import com.sure.ai.llamacpp.LlamaCppClient;
import com.sure.ai.llamacpp.LlamaCppModels;
import com.sure.ai.llamacpp.LlamaCppUtil;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * llama.cpp server 本地接入示例。
 *
 * <p>默认连接 {@code http://localhost:8080/v1}，无需 API Key；
 * 可用环境变量 {@code SURE_AI_LLAMACPP_BASE_URL} / {@code SURE_AI_LLAMACPP_API_KEY} 覆盖。</p>
 *
 * @author sureai
 * @since 1.3.0
 */
public final class LlamaCppDemo {

	private LlamaCppDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		String baseUrl = System.getenv("SURE_AI_LLAMACPP_BASE_URL");
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "http://localhost:8080/v1";
		}
		System.out.println("连接 llama.cpp server：" + baseUrl + "（若未启动请先运行 llama-server）");
		try {
			demoStaticChat();
			demoClientChat(baseUrl);
			demoStream();
		} catch (Exception e) {
			System.out.println("LlamaCpp 示例执行异常：" + e.getMessage());
		}
	}

	private static void demoStaticChat() {
		System.out.println("=== 1. 静态工具对话 ===");
		ChatResponse resp = LlamaCppUtil.chat(LlamaCppModels.LLAMA_3_1_8B, "你好，用一句话介绍你自己。");
		System.out.println(resp.firstText());
	}

	private static void demoClientChat(String baseUrl) {
		System.out.println("=== 2. Builder + Client 对话 ===");
		AiConfig config = AiConfig.builder()
			.apiKey(LlamaCppClient.DEFAULT_API_KEY)
			.baseUrl(baseUrl)
			.build();
		LlamaCppClient client = new LlamaCppClient(config);
		ChatResponse resp = client.chat(LlamaCppModels.LLAMA_3_1_8B, "用一句话回答：1+1等于几？");
		System.out.println(resp.firstText());
		client.close();
	}

	private static void demoStream() {
		System.out.println("=== 3. 流式对话 ===");
		ChatRequest req = ChatRequest.builder()
			.model(LlamaCppModels.LLAMA_3_1_8B)
			.messages(List.of(ChatMessage.user("用三个字说一种水果。")))
			.build();
		StringBuilder sb = new StringBuilder();
		LlamaCppUtil.chatStream(req, new Consumer<ChatStreamChunk>() {
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
