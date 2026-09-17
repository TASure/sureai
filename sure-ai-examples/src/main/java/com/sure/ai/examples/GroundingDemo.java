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
import com.sure.ai.model.GroundingSource;
import com.sure.ai.openai.OpenAiUtil;

/**
 * Grounding 联网搜索使用示例。
 *
 * <p>演示 {@code ChatRequest.builder().grounding("web_search")} 开启联网搜索，
 * 并打印响应中的 {@code groundingSources}（引用来源标题 / 链接 / 摘要）。
 * OpenAI 下注入 {@code web_search} 工具，响应解析自 annotations。</p>
 *
 * <p>检查环境变量 {@code SURE_AI_OPENAI_API_KEY}，缺失时打印提示并优雅跳过。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class GroundingDemo {

	private GroundingDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== Grounding 联网搜索（web_search）===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			ChatRequest req = ChatRequest.builder()
				.model("gpt-4o-mini")
				.messages(List.of(ChatMessage.user("今天有哪些科技圈的重要新闻？")))
				.grounding("web_search")
				.build();

			ChatResponse resp = OpenAiUtil.chat(req);

			System.out.println("  [回答]");
			System.out.println("    " + resp.firstText());

			List<GroundingSource> sources = resp.groundingSources();
			System.out.println("  [引用来源 " + sources.size() + " 条]");
			if (sources.isEmpty()) {
				System.out.println("    （无：当前模型/账号未返回联网来源）");
			}
			int i = 1;
			for (GroundingSource s : sources) {
				System.out.printf("    #%d  %s%n      %s%n", i++, s.title(), s.url());
			}
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
