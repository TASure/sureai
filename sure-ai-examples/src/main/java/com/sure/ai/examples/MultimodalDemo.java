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
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.TextPart;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;

/**
 * 多模态图像理解使用示例。
 *
 * <p>演示 OpenAI 兼容平台通过内容块（MessagePart）把「文本 + 图片 URL」一起发给视觉模型，
 * 由模型描述图片内容。检查环境变量 {@code SURE_AI_OPENAI_API_KEY}，缺失时打印提示并优雅跳过。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class MultimodalDemo {

	/** 示例图片 URL（公开可访问的样例图片）。 */
	private static final String IMAGE_URL =
		"https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/640px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg";

	private MultimodalDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== OpenAI 多模态图像理解（文本 + 图片 URL） ===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			ChatRequest req = ChatRequest.builder()
				// gpt-4o 系列原生支持视觉输入
				.model(OpenAiModels.GPT_4O)
				.messages(List.of(
					ChatMessage.user(List.of(
						TextPart.of("请用一两句话描述这张图片里的内容。"),
						ImagePart.ofUrl(IMAGE_URL)
					))
				))
				.build();

			ChatResponse resp = OpenAiUtil.chat(req);
			System.out.println("  图片 URL：" + IMAGE_URL);
			System.out.println("  模型描述：" + resp.firstText());
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
