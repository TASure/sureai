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
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;

/**
 * OpenAI 平台使用示例。
 *
 * <p>演示：静态工具调用、Builder + Client、流式、Function Calling、Embedding。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class OpenAiDemo {

	private OpenAiDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("请设置 SURE_AI_OPENAI_API_KEY，获取地址：https://platform.openai.com/api-keys");
			return;
		}
		try {
			demoStaticChat();
			demoClientChat();
			demoStream();
			demoFunctionCalling();
			demoEmbedding();
		} catch (Exception e) {
			System.out.println("OpenAI 示例执行异常：" + e.getMessage());
		}
	}

	private static void demoStaticChat() {
		System.out.println("=== 1. 静态工具对话 ===");
		ChatResponse resp = OpenAiUtil.chat(OpenAiModels.GPT_4O_MINI, "你好，用一句话介绍你自己。");
		System.out.println(resp.firstText());
	}

	private static void demoClientChat() {
		System.out.println("=== 2. Builder + Client 对话 ===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		AiConfig config = AiConfig.builder().apiKey(apiKey).build();
		OpenAiClient client = new OpenAiClient(config);
		ChatResponse resp = client.chat(OpenAiModels.GPT_4O_MINI, "用一句话回答：1+1等于几？");
		System.out.println(resp.firstText());
		client.close();
	}

	private static void demoStream() {
		System.out.println("=== 3. 流式对话 ===");
		ChatRequest req = ChatRequest.builder()
			.model(OpenAiModels.GPT_4O_MINI)
			.messages(List.of(ChatMessage.user("用三个字说一个水果。")))
			.build();
		StringBuilder sb = new StringBuilder();
		OpenAiUtil.chatStream(req, new Consumer<ChatStreamChunk>() {
			@Override
			public void accept(ChatStreamChunk chunk) {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			}
		});
		System.out.println(sb);
	}

	private static void demoFunctionCalling() {
		System.out.println("=== 4. Function Calling ===");
		ToolFunction weatherFn = ToolFunction.of(
			"getWeather",
			"查询指定城市的当前天气",
			"{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名称\"}},\"required\":[\"city\"]}"
		);
		List<ToolSpec> tools = List.of(ToolSpec.of(weatherFn));
		ChatRequest req = ChatRequest.builder()
			.model(OpenAiModels.GPT_4O_MINI)
			.messages(List.of(ChatMessage.user("上海今天天气怎么样？")))
			.tools(tools)
			.build();
		ChatResponse resp = OpenAiUtil.chat(req);
		if (!resp.choices().isEmpty()) {
			List<ToolCall> calls = resp.choices().get(0).message().toolCalls();
			if (calls != null && !calls.isEmpty()) {
				for (ToolCall tc : calls) {
					System.out.println("  模型请求调用工具: " + tc.name() + "，参数: " + tc.argumentsJson());
				}
			} else {
				System.out.println("  模型直接回复: " + resp.firstText());
			}
		}
	}

	private static void demoEmbedding() {
		System.out.println("=== 5. Embedding 向量 ===");
		EmbeddingResponse resp = OpenAiUtil.embed(OpenAiModels.TEXT_EMBEDDING_3_SMALL, "这是一段测试文本");
		if (!resp.embeddings().isEmpty()) {
			float[] vec = resp.embeddings().get(0);
			System.out.println("  向量维度: " + vec.length + "，前5个值: " + vec[0] + ", " + vec[1] + ", " + vec[2] + ", " + vec[3] + ", " + vec[4]);
		}
	}
}
