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

import com.sure.ai.client.AiConfig;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.pipeline.RagPipeline;

/**
 * RAG（检索增强生成）使用示例。
 *
 * <p>演示：构建检索增强管线 → 摄入知识文档 → 基于知识库问答。
 * 对话与向量化均使用 OpenAI（需 SURE_AI_OPENAI_API_KEY），
 * 也可替换为 sureai 任意支持对话与 Embedding 的平台模块。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class RagDemo {

	private RagDemo() {
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
			System.out.println("[RagDemo] 未检测到 SURE_AI_OPENAI_API_KEY 环境变量，跳过。");
			System.out.println("[RagDemo] 获取 Key：https://platform.openai.com/api-keys");
			return;
		}
		OpenAiClient client = new OpenAiClient(AiConfig.builder().apiKey(apiKey).build());
		RagPipeline pipeline = RagUtil.pipeline(client,
				client, OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL);

		pipeline.ingest("sureai-intro",
				"sureai 是一个零第三方依赖的 Java 大模型接入工具库。"
						+ "每个主流 AI 平台对应一个独立 Maven 模块与静态入口工具类。"
						+ "sureai 支持流式对话、Function Calling、Embedding 与 RAG 检索增强问答。");
		System.out.println("[RagDemo] 知识库摄入完成，向量数：" + pipeline.vectorStore().size());

		ChatResponse response = pipeline.ask("sureai 支持哪些能力？", 3);
		System.out.println("[RagDemo] 回答：" + response.firstText());

		client.close();
	}
}
