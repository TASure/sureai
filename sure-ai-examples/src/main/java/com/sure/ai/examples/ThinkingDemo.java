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

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.openai.OpenAiUtil;

/**
 * 思考模式（Reasoning / Thinking）使用示例。
 *
 * <p>演示 {@code ChatRequest.builder().reasoningEffort("high")} 开启深度思考，
 * 并打印响应中的 {@code reasoningContent}（模型的思维链）。OpenAI 下序列化为
 * {@code reasoning_effort}，响应解析自 {@code reasoning_content}。</p>
 *
 * <p>检查环境变量 {@code SURE_AI_OPENAI_API_KEY}，缺失时打印提示并优雅跳过。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class ThinkingDemo {

	/** 思考模型（OpenAI reasoning_effort 仅对推理模型生效，按需替换）。 */
	private static final String MODEL = "o4-mini";

	private ThinkingDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== 思考模式（reasoningEffort=high）===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			ChatRequest req = ChatRequest.builder()
				.model(MODEL)
				.messages(List.of(ChatMessage.user(
					"一个水池，进水管 5 小时注满，出水管 8 小时放空。两管同开，几小时注满？")))
				.reasoningEffort("high")
				.build();

			ChatResponse resp = OpenAiUtil.chat(req);
			if (resp.choices().isEmpty()) {
				System.out.println("  无响应内容");
				return;
			}
			ChatMessage message = resp.choices().get(0).message();

			System.out.println("  [思维链 reasoningContent]");
			String reasoning = message.reasoningContent();
			System.out.println(reasoning == null || reasoning.isBlank()
				? "    （无：当前模型/账号未返回思维链）"
				: "    " + reasoning);

			System.out.println("  [正式回答]");
			System.out.println("    " + resp.firstText());
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
