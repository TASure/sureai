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

import com.sure.ai.bedrock.BedrockClient;
import com.sure.ai.bedrock.BedrockModels;
import com.sure.ai.bedrock.BedrockUtil;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * AWS Bedrock 平台使用示例。
 *
 * <p>演示：环境变量凭证创建客户端、Converse 非流式对话。缺凭证时优雅跳过。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class BedrockDemo {

	private BedrockDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		String ak = System.getenv("SURE_AI_BEDROCK_ACCESS_KEY");
		String awsAk = System.getenv("AWS_ACCESS_KEY_ID");
		if ((ak == null || ak.isBlank()) && (awsAk == null || awsAk.isBlank())) {
			System.out.println("未检测到 AWS 凭证：请配置 SURE_AI_BEDROCK_ACCESS_KEY / SURE_AI_BEDROCK_SECRET_KEY "
				+ "/ SURE_AI_BEDROCK_REGION（或 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION）后再运行。");
			return;
		}
		try {
			demoChat();
		} catch (Exception e) {
			System.out.println("Bedrock 示例执行异常：" + e.getMessage());
		}
	}

	private static void demoChat() {
		System.out.println("=== Bedrock Converse 对话 ===");
		BedrockClient client = BedrockUtil.create();
		ChatResponse resp = client.chat(ChatRequest.builder()
			.model(BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET)
			.messages(List.of(ChatMessage.user("用一句话介绍你自己。")))
			.build());
		System.out.println(resp.firstText());
		client.close();
	}
}
